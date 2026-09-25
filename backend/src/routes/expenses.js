'use strict';

const express = require('express');
const { pool, withTransaction } = require('../db/pool');
const { ApiError, asyncHandler } = require('../middleware/errors');
const { authenticate } = require('../middleware/auth');
const {
  requireString,
  optionalString,
  requireAmount,
  requireId,
  optionalDate,
  todayIso
} = require('../middleware/validate');
const { assertMonthOpen, monthOfDate } = require('../services/closures');

const router = express.Router();

const EXPENSE_SELECT = `
  SELECT e.id, e.group_id, e.category_id, c.name AS category_name, c.icon AS category_icon,
         e.description, e.amount, e.expense_date, e.status,
         e.paid_by,     pb.name AS paid_by_name,
         e.split_to,    st.name AS split_to_name,
         e.created_by,  cb.name AS created_by_name,
         e.approved_by, ab.name AS approved_by_name,
         e.approved_at, e.rejection_reason, e.created_at, e.updated_at
    FROM expenses e
    JOIN categories c ON c.id = e.category_id
    JOIN users pb ON pb.id = e.paid_by
    JOIN users st ON st.id = e.split_to
    JOIN users cb ON cb.id = e.created_by
    LEFT JOIN users ab ON ab.id = e.approved_by
`;

function mapExpense(row) {
  return {
    id: Number(row.id),
    groupId: Number(row.group_id),
    categoryId: Number(row.category_id),
    categoryName: row.category_name,
    categoryIcon: row.category_icon,
    description: row.description,
    amount: row.amount,
    expenseDate: row.expense_date,
    status: row.status,
    paidBy: { id: Number(row.paid_by), name: row.paid_by_name },
    splitTo: { id: Number(row.split_to), name: row.split_to_name },
    createdBy: { id: Number(row.created_by), name: row.created_by_name },
    approvedBy: row.approved_by ? { id: Number(row.approved_by), name: row.approved_by_name } : null,
    approvedAt: row.approved_at,
    rejectionReason: row.rejection_reason,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

/** Loads an expense and proves the caller belongs to its group. */
async function loadExpenseContext(conn, expenseId, userId) {
  const [rows] = await conn.query(
    `SELECT e.*, g.admin_id
       FROM expenses e
       JOIN \`groups\` g ON g.id = e.group_id
       JOIN group_members gm ON gm.group_id = e.group_id AND gm.user_id = ? AND gm.status = 'active'
      WHERE e.id = ?`,
    [userId, expenseId]
  );
  if (rows.length === 0) {
    // Same 404 whether it does not exist or belongs to another flat — a 403
    // here would confirm that someone else's expense id is real.
    throw new ApiError(404, 'Expense not found');
  }
  const expense = rows[0];
  return { expense, isAdmin: Number(expense.admin_id) === Number(userId) };
}

async function writeAudit(conn, { expenseId, action, fromStatus, toStatus, detail, actorId }) {
  await conn.query(
    `INSERT INTO expense_audit (expense_id, action, from_status, to_status, detail, actor_id)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [expenseId, action, fromStatus || null, toStatus || null, detail || null, actorId]
  );
}

/** GET /api/expenses/:expenseId */
router.get(
  '/:expenseId',
  authenticate,
  asyncHandler(async (req, res) => {
    const expenseId = Number(req.params.expenseId);
    if (!Number.isInteger(expenseId) || expenseId <= 0) {
      throw new ApiError(400, 'Invalid expense id');
    }

    const { isAdmin } = await loadExpenseContext(pool, expenseId, req.user.id);

    const [rows] = await pool.query(`${EXPENSE_SELECT} WHERE e.id = ?`, [expenseId]);
    const [audit] = await pool.query(
      `SELECT a.id, a.action, a.from_status, a.to_status, a.detail, a.created_at,
              u.id AS actor_id, u.name AS actor_name
         FROM expense_audit a
         JOIN users u ON u.id = a.actor_id
        WHERE a.expense_id = ?
        ORDER BY a.created_at ASC, a.id ASC`,
      [expenseId]
    );

    res.json({
      expense: mapExpense(rows[0]),
      canApprove: isAdmin && rows[0].status === 'pending',
      audit: audit.map((a) => ({
        id: Number(a.id),
        action: a.action,
        fromStatus: a.from_status,
        toStatus: a.to_status,
        detail: a.detail,
        actor: { id: Number(a.actor_id), name: a.actor_name },
        createdAt: a.created_at
      }))
    });
  })
);

/**
 * PATCH /api/expenses/:expenseId
 *
 * Section 15: an approved expense must not be edited silently. Editing one
 * sends it back to Pending for re-approval, and the change is audited either
 * way. Only the person who created it, or the Admin, may edit at all.
 */
router.patch(
  '/:expenseId',
  authenticate,
  asyncHandler(async (req, res) => {
    const expenseId = Number(req.params.expenseId);
    if (!Number.isInteger(expenseId) || expenseId <= 0) {
      throw new ApiError(400, 'Invalid expense id');
    }

    const updated = await withTransaction(async (conn) => {
      const { expense, isAdmin } = await loadExpenseContext(conn, expenseId, req.user.id);

      const isAuthor = Number(expense.created_by) === Number(req.user.id);
      if (!isAuthor && !isAdmin) {
        throw new ApiError(403, 'Only the member who added this expense, or the Admin, can edit it');
      }
      if (expense.status === 'cancelled') {
        throw new ApiError(409, 'A cancelled expense cannot be edited');
      }

      // Both the month it sits in now and any month it is being moved to must
      // be open — otherwise an edit could either alter a settled month's
      // totals or push a figure into one.
      await assertMonthOpen(
        conn,
        expense.group_id,
        monthOfDate(expense.expense_date),
        'This expense'
      );
      if (req.body.expenseDate !== undefined) {
        const moved = optionalDate(req.body, 'expenseDate', expense.expense_date);
        await assertMonthOpen(conn, expense.group_id, monthOfDate(moved), 'That date');
      }

      const fields = {};
      if (req.body.categoryId !== undefined) {
        const categoryId = requireId(req.body, 'categoryId');
        const [cat] = await conn.query(
          'SELECT id FROM categories WHERE id = ? AND group_id = ? AND is_active = 1',
          [categoryId, expense.group_id]
        );
        if (cat.length === 0) throw new ApiError(400, 'Category is not available in this group');
        fields.category_id = categoryId;
      }
      if (req.body.description !== undefined) {
        fields.description = requireString(req.body, 'description');
      }
      if (req.body.amount !== undefined) {
        fields.amount = requireAmount(req.body);
      }
      if (req.body.expenseDate !== undefined) {
        fields.expense_date = optionalDate(req.body, 'expenseDate', expense.expense_date);
      }
      for (const [key, column] of [['paidBy', 'paid_by'], ['splitTo', 'split_to']]) {
        if (req.body[key] === undefined) continue;
        const userId = requireId(req.body, key);
        const [member] = await conn.query(
          `SELECT user_id FROM group_members
            WHERE group_id = ? AND user_id = ? AND status = 'active'`,
          [expense.group_id, userId]
        );
        if (member.length === 0) {
          throw new ApiError(400, `${key} must be an active member of this group`);
        }
        fields[column] = userId;
      }

      if (Object.keys(fields).length === 0) {
        throw new ApiError(400, 'No changes supplied');
      }

      // An edit to an already-decided expense re-opens it for approval.
      const reopens = expense.status === 'approved' || expense.status === 'rejected';
      if (reopens) {
        fields.status = 'pending';
        fields.approved_by = null;
        fields.approved_at = null;
        fields.rejection_reason = null;
      }

      const assignments = Object.keys(fields).map((k) => `${k} = ?`).join(', ');
      await conn.query(`UPDATE expenses SET ${assignments} WHERE id = ?`, [
        ...Object.values(fields),
        expenseId
      ]);

      await writeAudit(conn, {
        expenseId,
        action: reopens ? 'edited_reopened' : 'edited',
        fromStatus: expense.status,
        toStatus: reopens ? 'pending' : expense.status,
        detail: JSON.stringify(Object.keys(fields)),
        actorId: req.user.id
      });

      const [rows] = await conn.query(`${EXPENSE_SELECT} WHERE e.id = ?`, [expenseId]);
      return { expense: mapExpense(rows[0]), reopened: reopens };
    });

    res.json(updated);
  })
);

/**
 * Approve / reject / cancel share one shape: Admin-only, status-guarded,
 * audited. Section 3: "Only the Admin can approve or reject expenses."
 */
function decisionRoute(action, { from, to, requiresReason = false }) {
  return asyncHandler(async (req, res) => {
    const expenseId = Number(req.params.expenseId);
    if (!Number.isInteger(expenseId) || expenseId <= 0) {
      throw new ApiError(400, 'Invalid expense id');
    }

    const reason = requiresReason
      ? requireString(req.body, 'reason', { max: 255 })
      : optionalString(req.body, 'reason', { max: 255 });

    const result = await withTransaction(async (conn) => {
      const { expense, isAdmin } = await loadExpenseContext(conn, expenseId, req.user.id);

      if (!isAdmin) throw new ApiError(403, 'Only the group Admin can do this');

      // Approving or rejecting moves money into or out of the month's
      // approved total, so a settled month has to be reopened first.
      await assertMonthOpen(
        conn,
        expense.group_id,
        monthOfDate(expense.expense_date),
        'This expense'
      );

      if (!from.includes(expense.status)) {
        throw new ApiError(
          409,
          `An expense that is ${expense.status} cannot be ${action}. Expected: ${from.join(' or ')}.`
        );
      }

      await conn.query(
        `UPDATE expenses
            SET status = ?, approved_by = ?, approved_at = CURRENT_TIMESTAMP, rejection_reason = ?
          WHERE id = ?`,
        [to, req.user.id, to === 'approved' ? null : reason, expenseId]
      );

      await writeAudit(conn, {
        expenseId,
        action,
        fromStatus: expense.status,
        toStatus: to,
        detail: reason,
        actorId: req.user.id
      });

      const [rows] = await conn.query(`${EXPENSE_SELECT} WHERE e.id = ?`, [expenseId]);
      return mapExpense(rows[0]);
    });

    res.json({ expense: result });
  });
}

router.post(
  '/:expenseId/approve',
  authenticate,
  decisionRoute('approved', { from: ['pending'], to: 'approved' })
);

router.post(
  '/:expenseId/reject',
  authenticate,
  decisionRoute('rejected', { from: ['pending'], to: 'rejected', requiresReason: true })
);

// Table 4: an optional status for an expense cancelled after approval.
router.post(
  '/:expenseId/cancel',
  authenticate,
  decisionRoute('cancelled', { from: ['pending', 'approved'], to: 'cancelled', requiresReason: true })
);

module.exports = { router, EXPENSE_SELECT, mapExpense, writeAudit, todayIso };
