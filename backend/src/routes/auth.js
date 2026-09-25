'use strict';

const express = require('express');
const bcrypt = require('bcryptjs');
const { pool, withTransaction } = require('../db/pool');
const { ApiError, asyncHandler } = require('../middleware/errors');
const { signToken, authenticate } = require('../middleware/auth');
const {
  requireString,
  optionalString,
  requireEmail,
  requirePassword
} = require('../middleware/validate');
const { DEFAULT_CATEGORIES } = require('../services/categories');

const router = express.Router();

function publicUser(row) {
  return {
    id: Number(row.id),
    name: row.name,
    email: row.email,
    phone: row.phone,
    role: row.role,
    status: row.status
  };
}

/**
 * POST /api/auth/register
 *
 * Creates an account. Passing groupName also creates a flat with this user as
 * its Admin, seeded with the section 8 categories — that is how the very first
 * account bootstraps a group. Everyone else is added by the Admin from the
 * Members screen, which keeps the group closed to five known people.
 */
router.post(
  '/register',
  asyncHandler(async (req, res) => {
    const name = requireString(req.body, 'name', { max: 120 });
    const email = requireEmail(req.body);
    const phone = optionalString(req.body, 'phone', { max: 20 });
    const password = requirePassword(req.body);
    const groupName = optionalString(req.body, 'groupName', { max: 120 });

    // Section 16: passwords are only ever stored as a hash.
    const passwordHash = await bcrypt.hash(password, 10);

    const result = await withTransaction(async (conn) => {
      const [existing] = await conn.query('SELECT id FROM users WHERE email = ?', [email]);
      if (existing.length > 0) {
        throw new ApiError(409, 'An account with that email already exists');
      }

      const [userResult] = await conn.query(
        `INSERT INTO users (name, email, phone, password_hash, role)
         VALUES (?, ?, ?, ?, ?)`,
        [name, email, phone, passwordHash, groupName ? 'admin' : 'member']
      );
      const userId = userResult.insertId;

      let group = null;
      if (groupName) {
        const [groupResult] = await conn.query(
          'INSERT INTO `groups` (name, admin_id) VALUES (?, ?)',
          [groupName, userId]
        );
        const groupId = groupResult.insertId;

        await conn.query(
          'INSERT INTO group_members (group_id, user_id) VALUES (?, ?)',
          [groupId, userId]
        );

        // Section 8's starter list, so a new flat is usable immediately.
        const values = DEFAULT_CATEGORIES.map((c, index) => [groupId, c.name, c.icon, index]);
        await conn.query(
          'INSERT INTO categories (group_id, name, icon, sort_order) VALUES ?',
          [values]
        );

        group = { id: groupId, name: groupName, adminId: userId, isAdmin: true };
      }

      const [rows] = await conn.query(
        'SELECT id, name, email, phone, role, status FROM users WHERE id = ?',
        [userId]
      );
      return { user: rows[0], group };
    });

    res.status(201).json({
      token: signToken(result.user),
      user: publicUser(result.user),
      group: result.group
    });
  })
);

/** POST /api/auth/login */
router.post(
  '/login',
  asyncHandler(async (req, res) => {
    const email = requireEmail(req.body);
    const password = requirePassword(req.body);

    const [rows] = await pool.query(
      `SELECT id, name, email, phone, password_hash, role, status
         FROM users WHERE email = ?`,
      [email]
    );

    // Same message either way: a distinct "no such user" reply would let anyone
    // enumerate which emails have accounts.
    const invalid = new ApiError(401, 'Email or password is incorrect');
    if (rows.length === 0) throw invalid;

    const user = rows[0];
    const ok = await bcrypt.compare(password, user.password_hash);
    if (!ok) throw invalid;
    if (user.status !== 'active') throw new ApiError(403, 'Account is inactive');

    res.json({ token: signToken(user), user: publicUser(user) });
  })
);

/** GET /api/auth/me — the signed-in user plus every group they belong to. */
router.get(
  '/me',
  authenticate,
  asyncHandler(async (req, res) => {
    const [groups] = await pool.query(
      `SELECT g.id, g.name, g.admin_id
         FROM \`groups\` g
         JOIN group_members gm ON gm.group_id = g.id
        WHERE gm.user_id = ? AND gm.status = 'active'
        ORDER BY g.id`,
      [req.user.id]
    );

    res.json({
      user: publicUser(req.user),
      groups: groups.map((g) => ({
        id: Number(g.id),
        name: g.name,
        adminId: Number(g.admin_id),
        isAdmin: Number(g.admin_id) === Number(req.user.id)
      }))
    });
  })
);

/** POST /api/auth/change-password */
router.post(
  '/change-password',
  authenticate,
  asyncHandler(async (req, res) => {
    const currentPassword = requirePassword(req.body, 'currentPassword');
    const newPassword = requirePassword(req.body, 'newPassword');

    const [rows] = await pool.query('SELECT password_hash FROM users WHERE id = ?', [req.user.id]);
    const ok = await bcrypt.compare(currentPassword, rows[0].password_hash);
    if (!ok) throw new ApiError(400, 'Current password is incorrect');

    await pool.query('UPDATE users SET password_hash = ? WHERE id = ?', [
      await bcrypt.hash(newPassword, 10),
      req.user.id
    ]);

    res.json({ ok: true });
  })
);

module.exports = { router, publicUser };
