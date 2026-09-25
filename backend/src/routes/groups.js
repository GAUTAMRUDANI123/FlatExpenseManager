'use strict';

const express = require('express');
const bcrypt = require('bcryptjs');
const { pool, withTransaction } = require('../db/pool');
const { ApiError, asyncHandler } = require('../middleware/errors');
const { authenticate, requireGroupMember, requireGroupAdmin } = require('../middleware/auth');
const {
  requireString,
  optionalString,
  requireAmount,
  requireId,
  optionalDate,
  todayIso,
  normaliseMonth,
  currentMonth,
  requireEmail,
  requirePassword
} = require('../middleware/validate');
const { EXPENSE_SELECT, mapExpense, writeAudit } = require('./expenses');
const {
  nextMonthOf,
  isMonthClosed,
  assertMonthOpen,
  writeGroupAudit,
  carryOverPending
} = require('../services/closures');

const router = express.Router();
const MAX_GROUP_MEMBERS = Number(process.env.MAX_GROUP_MEMBERS || 5);

// Every route below is group-scoped: authenticated, then proven to be a member.
router.use('/:groupId', authenticate, requireGroupMember);

// ---------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------

/** GET /api/groups/:groupId/members */
router.get(
  '/:groupId/members',
  asyncHandler(async (req, res) => {
    const [rows] = await pool.query(
      `SELECT u.id, u.name, u.email, u.phone, gm.status, gm.joined_at
         FROM group_members gm
         JOIN users u ON u.id = gm.user_id
        WHERE gm.group_id = ?
        ORDER BY (u.id = ?) DESC, u.name ASC`,
      [req.group.id, req.group.adminId]
    );

    res.json({
      adminId: req.group.adminId,
      members: rows.map((r) => ({
        id: Number(r.id),
        name: r.name,
        email: r.email,
        phone: r.phone,
        status: r.status,
        joinedAt: r.joined_at,
        isAdmin: Number(r.id) === req.group.adminId
      }))
    });
  })
);

/**
 * POST /api/groups/:groupId/members  (Admin)
 *
 * The Admin provisions the other flatmates' accounts. Core rule: the group
 * contains five people, so this refuses the sixth active member.
 */
router.post(
  '/:groupId/members',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const name = requireString(req.body, 'name', { max: 120 });
    const email = requireEmail(req.body);
    const phone = optionalString(req.body, 'phone', { max: 20 });
    const password = requirePassword(req.body);
    const passwordHash = await bcrypt.hash(password, 10);

    const member = await withTransaction(async (conn) => {
      const [countRows] = await conn.query(
        `SELECT COUNT(*) AS n FROM group_members WHERE group_id = ? AND status = 'active'`,
        [req.group.id]
      );
      if (Number(countRows[0].n) >= MAX_GROUP_MEMBERS) {
        throw new ApiError(409, `This group already has its ${MAX_GROUP_MEMBERS} members`);
      }

      let [existing] = await conn.query('SELECT id FROM users WHERE email = ?', [email]);
      let userId;
      if (existing.length > 0) {
        userId = Number(existing[0].id);
        const [already] = await conn.query(
          'SELECT id FROM group_members WHERE group_id = ? AND user_id = ?',
          [req.group.id, userId]
        );
        if (already.length > 0) throw new ApiError(409, 'That person is already in this group');
      } else {
        const [result] = await conn.query(
          `INSERT INTO users (name, email, phone, password_hash, role)
           VALUES (?, ?, ?, ?, 'member')`,
          [name, email, phone, passwordHash]
        );
        userId = result.insertId;
      }

      await conn.query('INSERT INTO group_members (group_id, user_id) VALUES (?, ?)', [
        req.group.id,
        userId
      ]);

      const [rows] = await conn.query(
        'SELECT id, name, email, phone FROM users WHERE id = ?',
        [userId]
      );
      return rows[0];
    });

    res.status(201).json({
      member: {
        id: Number(member.id),
        name: member.name,
        email: member.email,
        phone: member.phone,
        status: 'active',
        isAdmin: false
      }
    });
  })
);

/** PATCH /api/groups/:groupId/members/:userId  (Admin) — activate/deactivate */
router.patch(
  '/:groupId/members/:userId',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const userId = Number(req.params.userId);
    const status = requireString(req.body, 'status');
    if (!['active', 'inactive'].includes(status)) {
      throw new ApiError(400, 'status must be active or inactive');
    }
    if (userId === req.group.adminId) {
      throw new ApiError(409, 'The Admin cannot be deactivated. Transfer admin first.');
    }

    const [result] = await pool.query(
      'UPDATE group_members SET status = ? WHERE group_id = ? AND user_id = ?',
      [status, req.group.id, userId]
    );
    if (result.affectedRows === 0) throw new ApiError(404, 'Member not found in this group');

    res.json({ ok: true, userId, status });
  })
);

/**
 * POST /api/groups/:groupId/transfer-admin  (Admin)
 * Core rule: "The group's admin_id identifies the current Admin", and exactly
 * one member is the Admin — so this moves it rather than adding a second.
 */
router.post(
  '/:groupId/transfer-admin',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const newAdminId = requireId(req.body, 'userId');

    await withTransaction(async (conn) => {
      const [rows] = await conn.query(
        `SELECT user_id FROM group_members
          WHERE group_id = ? AND user_id = ? AND status = 'active'`,
        [req.group.id, newAdminId]
      );
      if (rows.length === 0) throw new ApiError(400, 'That person is not an active member');

      await conn.query('UPDATE `groups` SET admin_id = ? WHERE id = ?', [newAdminId, req.group.id]);
      await conn.query(`UPDATE users SET role = 'member' WHERE id = ?`, [req.group.adminId]);
      await conn.query(`UPDATE users SET role = 'admin' WHERE id = ?`, [newAdminId]);
    });

    res.json({ ok: true, adminId: newAdminId });
  })
);

// ---------------------------------------------------------------------------
// Categories (section 8)
// ---------------------------------------------------------------------------

/** GET /api/groups/:groupId/categories — ?includeInactive=1 for the admin screen */
router.get(
  '/:groupId/categories',
  asyncHandler(async (req, res) => {
    const includeInactive = req.query.includeInactive === '1';
    const [rows] = await pool.query(
      `SELECT id, name, icon, is_active, sort_order
         FROM categories
        WHERE group_id = ? ${includeInactive ? '' : 'AND is_active = 1'}
        ORDER BY sort_order ASC, name ASC`,
      [req.group.id]
    );

    res.json({
      categories: rows.map((r) => ({
        id: Number(r.id),
        name: r.name,
        icon: r.icon,
        isActive: r.is_active === 1,
        sortOrder: r.sort_order
      }))
    });
  })
);

/** POST /api/groups/:groupId/categories  (Admin) */
router.post(
  '/:groupId/categories',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const name = requireString(req.body, 'name', { max: 80 });
    const icon = optionalString(req.body, 'icon', { max: 40 });

    const [dupe] = await pool.query(
      'SELECT id FROM categories WHERE group_id = ? AND name = ?',
      [req.group.id, name]
    );
    if (dupe.length > 0) throw new ApiError(409, 'That category already exists');

    const [maxRow] = await pool.query(
      'SELECT COALESCE(MAX(sort_order), -1) + 1 AS next FROM categories WHERE group_id = ?',
      [req.group.id]
    );

    const [result] = await pool.query(
      'INSERT INTO categories (group_id, name, icon, sort_order) VALUES (?, ?, ?, ?)',
      [req.group.id, name, icon, maxRow[0].next]
    );

    res.status(201).json({
      category: { id: result.insertId, name, icon, isActive: true, sortOrder: maxRow[0].next }
    });
  })
);

/** PATCH /api/groups/:groupId/categories/:categoryId  (Admin) — rename, reorder, activate */
router.patch(
  '/:groupId/categories/:categoryId',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const categoryId = Number(req.params.categoryId);
    const fields = {};

    if (req.body.name !== undefined) fields.name = requireString(req.body, 'name', { max: 80 });
    if (req.body.icon !== undefined) fields.icon = optionalString(req.body, 'icon', { max: 40 });
    if (req.body.isActive !== undefined) {
      if (typeof req.body.isActive !== 'boolean') throw new ApiError(400, 'isActive must be true or false');
      fields.is_active = req.body.isActive ? 1 : 0;
    }
    if (req.body.sortOrder !== undefined) {
      const order = Number(req.body.sortOrder);
      if (!Number.isInteger(order)) throw new ApiError(400, 'sortOrder must be a whole number');
      fields.sort_order = order;
    }
    if (Object.keys(fields).length === 0) throw new ApiError(400, 'No changes supplied');

    const assignments = Object.keys(fields).map((k) => `${k} = ?`).join(', ');
    const [result] = await pool.query(
      `UPDATE categories SET ${assignments} WHERE id = ? AND group_id = ?`,
      [...Object.values(fields), categoryId, req.group.id]
    );
    if (result.affectedRows === 0) throw new ApiError(404, 'Category not found');

    res.json({ ok: true, categoryId });
  })
);

// ---------------------------------------------------------------------------
// Monthly contributions (section 4)
// ---------------------------------------------------------------------------

/** GET /api/groups/:groupId/contributions?month=YYYY-MM */
router.get(
  '/:groupId/contributions',
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.query.month, currentMonth());

    // Left join from members so a member with no row yet still appears, as
    // Table 2 shows them: expected amount, status, paid date.
    const [rows] = await pool.query(
      `SELECT u.id AS user_id, u.name,
              COALESCE(mc.expected_amount, 0) AS expected_amount,
              COALESCE(mc.paid_amount, 0)     AS paid_amount,
              COALESCE(mc.status, 'pending')  AS status,
              mc.paid_at, mc.note
         FROM group_members gm
         JOIN users u ON u.id = gm.user_id
         LEFT JOIN monthly_contributions mc
                ON mc.group_id = gm.group_id AND mc.user_id = gm.user_id AND mc.month = ?
        WHERE gm.group_id = ? AND gm.status = 'active'
        ORDER BY u.name ASC`,
      [month, req.group.id]
    );

    const expected = rows.reduce((sum, r) => sum + Number(r.expected_amount), 0);
    const received = rows.reduce((sum, r) => sum + Number(r.paid_amount), 0);

    res.json({
      month: month.slice(0, 7),
      totals: {
        expected: expected.toFixed(2),
        received: received.toFixed(2),
        pending: Math.max(0, expected - received).toFixed(2)
      },
      contributions: rows.map((r) => ({
        userId: Number(r.user_id),
        name: r.name,
        isAdmin: Number(r.user_id) === req.group.adminId,
        expectedAmount: Number(r.expected_amount).toFixed(2),
        paidAmount: Number(r.paid_amount).toFixed(2),
        status: r.status,
        paidAt: r.paid_at,
        note: r.note
      }))
    });
  })
);

/**
 * POST /api/groups/:groupId/contributions  (Admin)
 *
 * Records what a member has actually paid into the Admin's account, or sets the
 * expected amount for the month. The Admin is the central account holder, so
 * only the Admin can confirm money received.
 */
router.post(
  '/:groupId/contributions',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.body.month, currentMonth());
    const userId = requireId(req.body, 'userId');
    const note = optionalString(req.body, 'note', { max: 255 });

    const hasExpected = req.body.expectedAmount !== undefined;
    const hasPaid = req.body.paidAmount !== undefined;
    if (!hasExpected && !hasPaid) {
      throw new ApiError(400, 'Supply expectedAmount, paidAmount, or both');
    }

    const result = await withTransaction(async (conn) => {
      await assertMonthOpen(conn, req.group.id, month, 'This contribution');

      const [member] = await conn.query(
        `SELECT user_id FROM group_members
          WHERE group_id = ? AND user_id = ? AND status = 'active'`,
        [req.group.id, userId]
      );
      if (member.length === 0) throw new ApiError(400, 'That person is not an active member');

      const [existingRows] = await conn.query(
        `SELECT expected_amount, paid_amount FROM monthly_contributions
          WHERE group_id = ? AND user_id = ? AND month = ?`,
        [req.group.id, userId, month]
      );
      const existing = existingRows[0] || { expected_amount: '0.00', paid_amount: '0.00' };

      const expected = hasExpected
        ? requireAmount(req.body, 'expectedAmount')
        : existing.expected_amount;
      // paidAmount may legitimately be 0 (undoing a mistaken entry), so it is
      // not run through requireAmount, which insists on > 0.
      let paid = existing.paid_amount;
      if (hasPaid) {
        const value = Number(req.body.paidAmount);
        if (!Number.isFinite(value) || value < 0) {
          throw new ApiError(400, 'paidAmount must be zero or more');
        }
        paid = value.toFixed(2);
      }

      const expectedNum = Number(expected);
      const paidNum = Number(paid);
      let status = 'pending';
      if (paidNum > 0 && paidNum >= expectedNum) status = 'paid';
      else if (paidNum > 0) status = 'partial';

      await conn.query(
        `INSERT INTO monthly_contributions
           (group_id, user_id, month, expected_amount, paid_amount, status, paid_at, note, recorded_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           expected_amount = VALUES(expected_amount),
           paid_amount     = VALUES(paid_amount),
           status          = VALUES(status),
           paid_at         = VALUES(paid_at),
           note            = VALUES(note),
           recorded_by     = VALUES(recorded_by)`,
        [
          req.group.id,
          userId,
          month,
          expected,
          paid,
          status,
          paidNum > 0 ? new Date() : null,
          note,
          req.user.id
        ]
      );

      return { userId, month: month.slice(0, 7), expectedAmount: expected, paidAmount: paid, status };
    });

    res.status(201).json({ contribution: result });
  })
);

/**
 * POST /api/groups/:groupId/contributions/bulk-expected  (Admin)
 * Sets the same expected amount for every active member — the usual start-of-
 * month action described in section 4.
 */
router.post(
  '/:groupId/contributions/bulk-expected',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.body.month, currentMonth());
    const expected = requireAmount(req.body, 'expectedAmount');

    const count = await withTransaction(async (conn) => {
      await assertMonthOpen(conn, req.group.id, month, 'This contribution');

      const [members] = await conn.query(
        `SELECT user_id FROM group_members WHERE group_id = ? AND status = 'active'`,
        [req.group.id]
      );
      for (const m of members) {
        await conn.query(
          `INSERT INTO monthly_contributions
             (group_id, user_id, month, expected_amount, recorded_by)
           VALUES (?, ?, ?, ?, ?)
           ON DUPLICATE KEY UPDATE expected_amount = VALUES(expected_amount)`,
          [req.group.id, m.user_id, month, expected, req.user.id]
        );
      }
      return members.length;
    });

    res.json({ ok: true, month: month.slice(0, 7), expectedAmount: expected, membersUpdated: count });
  })
);

// ---------------------------------------------------------------------------
// Expenses
// ---------------------------------------------------------------------------

/**
 * POST /api/groups/:groupId/expenses
 *
 * Section 5 + Table 3. splitTo defaults to the group Admin when the client
 * omits it, which is the server-side half of "Split To is pre-selected with the
 * Admin's name". Every new expense starts Pending (section 3).
 */
router.post(
  '/:groupId/expenses',
  asyncHandler(async (req, res) => {
    const categoryId = requireId(req.body, 'categoryId');
    const description = requireString(req.body, 'description');
    const amount = requireAmount(req.body);
    const expenseDate = optionalDate(req.body, 'expenseDate', todayIso());
    const paidBy = req.body.paidBy === undefined ? Number(req.user.id) : requireId(req.body, 'paidBy');
    const splitTo = req.body.splitTo === undefined ? req.group.adminId : requireId(req.body, 'splitTo');

    const created = await withTransaction(async (conn) => {
      // Backdating into a settled month would change totals everyone has
      // already agreed, so the period is checked before anything else.
      await assertMonthOpen(
        conn,
        req.group.id,
        `${expenseDate.slice(0, 7)}-01`,
        'This expense'
      );

      const [cat] = await conn.query(
        'SELECT id FROM categories WHERE id = ? AND group_id = ? AND is_active = 1',
        [categoryId, req.group.id]
      );
      if (cat.length === 0) throw new ApiError(400, 'Category is not available in this group');

      // Section 15: Paid By and Split To must both be valid group members.
      const [members] = await conn.query(
        `SELECT user_id FROM group_members
          WHERE group_id = ? AND status = 'active' AND user_id IN (?, ?)`,
        [req.group.id, paidBy, splitTo]
      );
      const ids = new Set(members.map((m) => Number(m.user_id)));
      if (!ids.has(paidBy)) throw new ApiError(400, 'paidBy must be an active member of this group');
      if (!ids.has(splitTo)) throw new ApiError(400, 'splitTo must be an active member of this group');

      const [result] = await conn.query(
        `INSERT INTO expenses
           (group_id, category_id, description, amount, paid_by, split_to,
            expense_date, status, created_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)`,
        [req.group.id, categoryId, description, amount, paidBy, splitTo, expenseDate, req.user.id]
      );

      await writeAudit(conn, {
        expenseId: result.insertId,
        action: 'created',
        toStatus: 'pending',
        actorId: req.user.id
      });

      const [rows] = await conn.query(`${EXPENSE_SELECT} WHERE e.id = ?`, [result.insertId]);
      return mapExpense(rows[0]);
    });

    res.status(201).json({ expense: created });
  })
);

/**
 * GET /api/groups/:groupId/expenses
 * Filters: status, month, categoryId, paidBy, splitTo, plus limit/offset.
 */
router.get(
  '/:groupId/expenses',
  asyncHandler(async (req, res) => {
    const where = ['e.group_id = ?'];
    const params = [req.group.id];

    if (req.query.status) {
      const statuses = String(req.query.status).split(',').map((s) => s.trim());
      const allowed = ['pending', 'approved', 'rejected', 'cancelled'];
      if (statuses.some((s) => !allowed.includes(s))) {
        throw new ApiError(400, `status must be one of ${allowed.join(', ')}`);
      }
      where.push(`e.status IN (${statuses.map(() => '?').join(',')})`);
      params.push(...statuses);
    }
    if (req.query.month) {
      const month = normaliseMonth(req.query.month, currentMonth());
      where.push('e.expense_date >= ? AND e.expense_date < DATE_ADD(?, INTERVAL 1 MONTH)');
      params.push(month, month);
    }
    for (const [param, column] of [
      ['categoryId', 'e.category_id'],
      ['paidBy', 'e.paid_by'],
      ['splitTo', 'e.split_to']
    ]) {
      if (req.query[param]) {
        const value = Number(req.query[param]);
        if (!Number.isInteger(value) || value <= 0) throw new ApiError(400, `Invalid ${param}`);
        where.push(`${column} = ?`);
        params.push(value);
      }
    }

    /**
     * Free-text search across the fields someone would actually remember:
     * what it was, which category, and who paid. LIKE is the right tool at
     * this size — a flat accumulates a few thousand expenses over years, and
     * a FULLTEXT index would not pay for itself. The term is escaped so that
     * a literal % or _ in a description does not turn into a wildcard.
     */
    if (req.query.q) {
      const term = String(req.query.q).trim();
      if (term.length > 0) {
        const escaped = term.replace(/[\\%_]/g, (ch) => `\\${ch}`);
        const like = `%${escaped}%`;
        where.push('(e.description LIKE ? OR c.name LIKE ? OR pb.name LIKE ?)');
        params.push(like, like, like);
      }
    }

    const limit = Math.min(Math.max(Number(req.query.limit) || 50, 1), 200);
    const offset = Math.max(Number(req.query.offset) || 0, 0);

    const [rows] = await pool.query(
      `${EXPENSE_SELECT} WHERE ${where.join(' AND ')}
       ORDER BY e.expense_date DESC, e.id DESC
       LIMIT ? OFFSET ?`,
      [...params, limit, offset]
    );
    // The same joins as EXPENSE_SELECT, because the search filter reaches into
    // the category and payer names. Both columns are NOT NULL with foreign
    // keys, so an inner join here cannot change the count.
    const [countRows] = await pool.query(
      `SELECT COUNT(*) AS n
         FROM expenses e
         JOIN categories c ON c.id = e.category_id
         JOIN users pb ON pb.id = e.paid_by
        WHERE ${where.join(' AND ')}`,
      params
    );

    res.json({
      total: Number(countRows[0].n),
      limit,
      offset,
      expenses: rows.map(mapExpense)
    });
  })
);

// ---------------------------------------------------------------------------
// Dashboard (section 9)
// ---------------------------------------------------------------------------

router.get(
  '/:groupId/dashboard',
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.query.month, currentMonth());
    const groupId = req.group.id;
    const monthRange = [month, month];

    const [
      [contributionRows],
      [approvedRows],
      [pendingRows],
      [categoryRows],
      [recentRows],
      [memberStatusRows]
    ] = await Promise.all([
      pool.query(
        `SELECT COALESCE(SUM(expected_amount), 0) AS expected,
                COALESCE(SUM(paid_amount), 0)     AS received
           FROM monthly_contributions WHERE group_id = ? AND month = ?`,
        [groupId, month]
      ),
      pool.query(
        `SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n
           FROM expenses
          WHERE group_id = ? AND status = 'approved'
            AND expense_date >= ? AND expense_date < DATE_ADD(?, INTERVAL 1 MONTH)`,
        [groupId, ...monthRange]
      ),
      pool.query(
        `SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n
           FROM expenses
          WHERE group_id = ? AND status = 'pending'
            AND expense_date >= ? AND expense_date < DATE_ADD(?, INTERVAL 1 MONTH)`,
        [groupId, ...monthRange]
      ),
      pool.query(
        `SELECT c.id, c.name, c.icon, COALESCE(SUM(e.amount), 0) AS total, COUNT(e.id) AS n
           FROM categories c
           LEFT JOIN expenses e
                  ON e.category_id = c.id AND e.status = 'approved'
                 AND e.expense_date >= ? AND e.expense_date < DATE_ADD(?, INTERVAL 1 MONTH)
          WHERE c.group_id = ?
          GROUP BY c.id, c.name, c.icon
         HAVING total > 0
          ORDER BY total DESC`,
        [...monthRange, groupId]
      ),
      pool.query(
        `${EXPENSE_SELECT} WHERE e.group_id = ?
         ORDER BY e.created_at DESC, e.id DESC LIMIT 10`,
        [groupId]
      ),
      pool.query(
        `SELECT u.id, u.name,
                COALESCE(mc.expected_amount, 0) AS expected_amount,
                COALESCE(mc.paid_amount, 0)     AS paid_amount,
                COALESCE(mc.status, 'pending')  AS status,
                mc.paid_at
           FROM group_members gm
           JOIN users u ON u.id = gm.user_id
           LEFT JOIN monthly_contributions mc
                  ON mc.group_id = gm.group_id AND mc.user_id = gm.user_id AND mc.month = ?
          WHERE gm.group_id = ? AND gm.status = 'active'
          ORDER BY u.name ASC`,
        [month, groupId]
      )
    ]);

    const expected = Number(contributionRows[0].expected);
    const received = Number(contributionRows[0].received);
    const approvedTotal = Number(approvedRows[0].total);

    res.json({
      month: month.slice(0, 7),
      group: { id: groupId, name: req.group.name, adminId: req.group.adminId },
      isAdmin: req.isGroupAdmin,
      contributions: {
        expected: expected.toFixed(2),
        received: received.toFixed(2),
        pending: Math.max(0, expected - received).toFixed(2)
      },
      expenses: {
        approvedTotal: approvedTotal.toFixed(2),
        approvedCount: Number(approvedRows[0].n),
        pendingTotal: Number(pendingRows[0].total).toFixed(2),
        pendingCount: Number(pendingRows[0].n)
      },
      // "Recorded/common balance": money actually received into the Admin's
      // account, less what has been approved as spent. Contributions not yet
      // paid are deliberately excluded — this is cash on hand, not a forecast.
      balance: (received - approvedTotal).toFixed(2),
      byCategory: categoryRows.map((r) => ({
        categoryId: Number(r.id),
        name: r.name,
        icon: r.icon,
        total: Number(r.total).toFixed(2),
        count: Number(r.n)
      })),
      recentExpenses: recentRows.map(mapExpense),
      memberStatus: memberStatusRows.map((r) => ({
        userId: Number(r.id),
        name: r.name,
        isAdmin: Number(r.id) === req.group.adminId,
        expectedAmount: Number(r.expected_amount).toFixed(2),
        paidAmount: Number(r.paid_amount).toFixed(2),
        status: r.status,
        paidAt: r.paid_at
      }))
    });
  })
);

// ---------------------------------------------------------------------------
// Monthly report (section 10)
// ---------------------------------------------------------------------------

router.get(
  '/:groupId/reports/monthly',
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.query.month, currentMonth());
    const groupId = req.group.id;
    const range = [month, month];

    const byStatusSql = `
      SELECT status, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS n
        FROM expenses
       WHERE group_id = ? AND expense_date >= ? AND expense_date < DATE_ADD(?, INTERVAL 1 MONTH)
       GROUP BY status`;

    const groupedSql = (joinColumn) => `
      SELECT u.id, u.name, COALESCE(SUM(e.amount), 0) AS total, COUNT(e.id) AS n
        FROM group_members gm
        JOIN users u ON u.id = gm.user_id
        LEFT JOIN expenses e
               ON e.${joinColumn} = u.id AND e.group_id = gm.group_id AND e.status = 'approved'
              AND e.expense_date >= ? AND e.expense_date < DATE_ADD(?, INTERVAL 1 MONTH)
       WHERE gm.group_id = ?
       GROUP BY u.id, u.name
       ORDER BY total DESC, u.name ASC`;

    const [
      [statusRows],
      [categoryRows],
      [paidByRows],
      [splitToRows],
      [contributionRows]
    ] = await Promise.all([
      pool.query(byStatusSql, [groupId, ...range]),
      pool.query(
        `SELECT c.id, c.name, COALESCE(SUM(e.amount), 0) AS total, COUNT(e.id) AS n
           FROM categories c
           LEFT JOIN expenses e
                  ON e.category_id = c.id AND e.status = 'approved'
                 AND e.expense_date >= ? AND e.expense_date < DATE_ADD(?, INTERVAL 1 MONTH)
          WHERE c.group_id = ?
          GROUP BY c.id, c.name
          ORDER BY total DESC, c.name ASC`,
        [...range, groupId]
      ),
      pool.query(groupedSql('paid_by'), [...range, groupId]),
      pool.query(groupedSql('split_to'), [...range, groupId]),
      pool.query(
        `SELECT COALESCE(SUM(expected_amount), 0) AS expected,
                COALESCE(SUM(paid_amount), 0)     AS received
           FROM monthly_contributions WHERE group_id = ? AND month = ?`,
        [groupId, month]
      )
    ]);

    const byStatus = { pending: '0.00', approved: '0.00', rejected: '0.00', cancelled: '0.00' };
    const counts = { pending: 0, approved: 0, rejected: 0, cancelled: 0 };
    for (const row of statusRows) {
      byStatus[row.status] = Number(row.total).toFixed(2);
      counts[row.status] = Number(row.n);
    }

    const received = Number(contributionRows[0].received);
    const expected = Number(contributionRows[0].expected);

    const shape = (rows) =>
      rows.map((r) => ({
        id: Number(r.id),
        name: r.name,
        total: Number(r.total).toFixed(2),
        count: Number(r.n)
      }));

    res.json({
      month: month.slice(0, 7),
      contributions: {
        expected: expected.toFixed(2),
        received: received.toFixed(2),
        pending: Math.max(0, expected - received).toFixed(2)
      },
      expenseTotals: { byStatus, counts },
      byCategory: categoryRows.map((r) => ({
        categoryId: Number(r.id),
        name: r.name,
        total: Number(r.total).toFixed(2),
        count: Number(r.n)
      })),
      byPaidBy: shape(paidByRows),
      bySplitTo: shape(splitToRows),
      balance: (received - Number(byStatus.approved)).toFixed(2)
    });
  })
);

// ---------------------------------------------------------------------------
// Month closing
// ---------------------------------------------------------------------------

/** GET /api/groups/:groupId/months — closure state, newest first. */
router.get(
  '/:groupId/months',
  asyncHandler(async (req, res) => {
    const [rows] = await pool.query(
      `SELECT c.month, c.closed_at, c.note, u.id AS closed_by_id, u.name AS closed_by_name
         FROM month_closures c
         JOIN users u ON u.id = c.closed_by
        WHERE c.group_id = ?
        ORDER BY c.month DESC`,
      [req.group.id]
    );

    const month = normaliseMonth(req.query.month, currentMonth());
    res.json({
      month,
      isClosed: rows.some((r) => r.month === month),
      closures: rows.map((r) => ({
        month: r.month,
        closedAt: r.closed_at,
        note: r.note,
        closedBy: { id: Number(r.closed_by_id), name: r.closed_by_name }
      }))
    });
  })
);

/**
 * POST /api/groups/:groupId/months/:month/close  (Admin)
 *
 * Expenses still awaiting a decision are moved into the next month rather than
 * blocking the close, so a closed month holds only settled figures and nothing
 * is silently rejected for having been missed.
 */
router.post(
  '/:groupId/months/:month/close',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.params.month, null);
    if (!month) throw new ApiError(400, 'month must be YYYY-MM');
    const note = optionalString(req.body, 'note', { max: 255 });

    const result = await withTransaction(async (conn) => {
      if (await isMonthClosed(conn, req.group.id, month)) {
        throw new ApiError(409, `${month.slice(0, 7)} is already closed`);
      }

      const carried = await carryOverPending(conn, req.group.id, month, req.user.id);

      await conn.query(
        'INSERT INTO month_closures (group_id, month, closed_by, note) VALUES (?, ?, ?, ?)',
        [req.group.id, month, req.user.id, note]
      );

      await writeGroupAudit(conn, {
        groupId: req.group.id,
        action: 'month_closed',
        detail:
          carried > 0
            ? `${month.slice(0, 7)} closed; ${carried} pending expense(s) carried into ${nextMonthOf(month).slice(0, 7)}`
            : `${month.slice(0, 7)} closed`,
        actorId: req.user.id
      });

      return carried;
    });

    res.json({ ok: true, month, carriedOver: result });
  })
);

/** POST /api/groups/:groupId/months/:month/reopen  (Admin) */
router.post(
  '/:groupId/months/:month/reopen',
  requireGroupAdmin,
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.params.month, null);
    if (!month) throw new ApiError(400, 'month must be YYYY-MM');
    const reason = requireString(req.body, 'reason', { max: 255 });

    await withTransaction(async (conn) => {
      const [result] = await conn.query(
        'DELETE FROM month_closures WHERE group_id = ? AND month = ?',
        [req.group.id, month]
      );
      if (result.affectedRows === 0) {
        throw new ApiError(409, `${month.slice(0, 7)} is not closed`);
      }

      await writeGroupAudit(conn, {
        groupId: req.group.id,
        action: 'month_reopened',
        detail: `${month.slice(0, 7)} reopened: ${reason}`,
        actorId: req.user.id
      });
    });

    res.json({ ok: true, month });
  })
);

// ---------------------------------------------------------------------------
// Activity log
// ---------------------------------------------------------------------------

/**
 * GET /api/groups/:groupId/activity
 *
 * One feed across both audit tables. expense_audit is expense-scoped and
 * group_audit records things that happen to the flat itself; the UI wants them
 * interleaved in time, so they are unioned here rather than in the client.
 */
router.get(
  '/:groupId/activity',
  asyncHandler(async (req, res) => {
    const limit = Math.min(Number(req.query.limit) || 100, 200);

    const [rows] = await pool.query(
      `(SELECT a.created_at, a.action, a.detail,
               e.id AS expense_id, e.description AS expense_description, e.amount,
               u.id AS actor_id, u.name AS actor_name
          FROM expense_audit a
          JOIN expenses e ON e.id = a.expense_id
          JOIN users u ON u.id = a.actor_id
         WHERE e.group_id = ?)
       UNION ALL
       (SELECT g.created_at, g.action, g.detail,
               NULL, NULL, NULL,
               u.id, u.name
          FROM group_audit g
          JOIN users u ON u.id = g.actor_id
         WHERE g.group_id = ?)
       ORDER BY created_at DESC
       LIMIT ?`,
      [req.group.id, req.group.id, limit]
    );

    res.json({
      activity: rows.map((r) => ({
        action: r.action,
        detail: r.detail,
        createdAt: r.created_at,
        actor: { id: Number(r.actor_id), name: r.actor_name },
        expense: r.expense_id
          ? {
              id: Number(r.expense_id),
              description: r.expense_description,
              amount: r.amount
            }
          : null
      }))
    });
  })
);

// ---------------------------------------------------------------------------
// Trend / monthly comparison
// ---------------------------------------------------------------------------

/**
 * GET /api/groups/:groupId/reports/trend?months=6
 *
 * Approved spend and contributions received per month, oldest first so the
 * client can plot it without reversing. Months with no activity are filled in
 * as zero rather than omitted — a line chart with gaps misreads as a dip.
 */
router.get(
  '/:groupId/reports/trend',
  asyncHandler(async (req, res) => {
    const months = Math.min(Math.max(Number(req.query.months) || 6, 2), 24);
    const end = normaliseMonth(req.query.month, currentMonth());

    // Walk back from the selected month to build the window.
    const window = [];
    let [y, m] = end.split('-').map(Number);
    for (let i = 0; i < months; i += 1) {
      window.unshift(`${y}-${String(m).padStart(2, '0')}-01`);
      m -= 1;
      if (m === 0) {
        m = 12;
        y -= 1;
      }
    }
    const first = window[0];
    const afterLast = nextMonthOf(window[window.length - 1]);

    const [spendRows] = await pool.query(
      `SELECT DATE_FORMAT(expense_date, '%Y-%m-01') AS month,
              SUM(amount) AS total, COUNT(*) AS count
         FROM expenses
        WHERE group_id = ? AND status = 'approved'
          AND expense_date >= ? AND expense_date < ?
        GROUP BY month`,
      [req.group.id, first, afterLast]
    );

    const [contribRows] = await pool.query(
      `SELECT month, SUM(paid_amount) AS total
         FROM monthly_contributions
        WHERE group_id = ? AND month >= ? AND month < ?
        GROUP BY month`,
      [req.group.id, first, afterLast]
    );

    const spend = new Map(spendRows.map((r) => [String(r.month), r]));
    const contrib = new Map(contribRows.map((r) => [String(r.month), r]));

    res.json({
      months: window.map((month) => {
        const s = spend.get(month);
        const c = contrib.get(month);
        return {
          month: month.slice(0, 7),
          spent: s ? Number(s.total).toFixed(2) : '0.00',
          count: s ? Number(s.count) : 0,
          received: c ? Number(c.total).toFixed(2) : '0.00'
        };
      })
    });
  })
);

// ---------------------------------------------------------------------------
// Settlement
// ---------------------------------------------------------------------------

/**
 * GET /api/groups/:groupId/settlement
 *
 * Where each member stands for the month: what they owe, what they have paid
 * in, and what they have laid out of their own pocket on approved expenses.
 *
 * `net` is (paid in + spent on the flat's behalf) - expected. Positive means
 * the flat owes them, negative means they owe the flat. Spending is counted
 * only when approved, for the same reason the balance excludes pending
 * expenses: an unapproved claim is not yet a debt.
 */
router.get(
  '/:groupId/settlement',
  asyncHandler(async (req, res) => {
    const month = normaliseMonth(req.query.month, currentMonth());
    const end = nextMonthOf(month);

    const [rows] = await pool.query(
      `SELECT u.id, u.name,
              COALESCE(mc.expected_amount, 0) AS expected,
              COALESCE(mc.paid_amount, 0)     AS paid,
              COALESCE((
                SELECT SUM(e.amount) FROM expenses e
                 WHERE e.group_id = gm.group_id AND e.paid_by = u.id
                   AND e.status = 'approved'
                   AND e.expense_date >= ? AND e.expense_date < ?
              ), 0) AS spent
         FROM group_members gm
         JOIN users u ON u.id = gm.user_id
         LEFT JOIN monthly_contributions mc
                ON mc.group_id = gm.group_id AND mc.user_id = u.id AND mc.month = ?
        WHERE gm.group_id = ? AND gm.status = 'active'
        ORDER BY u.name`,
      [month, end, month, req.group.id]
    );

    const members = rows.map((r) => {
      const expected = Number(r.expected);
      const paid = Number(r.paid);
      const spent = Number(r.spent);
      return {
        userId: Number(r.id),
        name: r.name,
        isAdmin: Number(r.id) === req.group.adminId,
        expected: expected.toFixed(2),
        paid: paid.toFixed(2),
        outstanding: Math.max(expected - paid, 0).toFixed(2),
        spent: spent.toFixed(2),
        net: (paid + spent - expected).toFixed(2)
      };
    });

    const sum = (key) => members.reduce((t, x) => t + Number(x[key]), 0).toFixed(2);

    res.json({
      month: month.slice(0, 7),
      isClosed: await isMonthClosed(pool, req.group.id, month),
      members,
      totals: {
        expected: sum('expected'),
        paid: sum('paid'),
        outstanding: sum('outstanding'),
        spent: sum('spent')
      }
    });
  })
);

module.exports = { router };
