'use strict';

const express = require('express');
const bcrypt = require('bcryptjs');
const { pool, withTransaction } = require('../db/pool');
const { ApiError, asyncHandler } = require('../middleware/errors');
const { signToken, authenticate } = require('../middleware/auth');
const {
  requireString,
  optionalString,
  optionalId,
  requireEmail,
  requirePassword
} = require('../middleware/validate');
const { seedCategories } = require('../services/categories');

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
/**
 * Open signup is only needed once, to create the very first flat. After that
 * the Admin adds each flatmate from the Members screen, so leaving it open on
 * a published API just lets anyone who finds the address create unlimited
 * accounts and groups in someone else's database.
 *
 * They could never read the flat's data — every group-scoped route checks
 * membership, and a stranger gets 403 — but a signup form on the public
 * internet that nobody needs is still a signup form on the public internet.
 *
 * Left unset it stays open, so a fresh install works out of the box. Set
 * ALLOW_REGISTRATION=false once the flat exists.
 */
const REGISTRATION_OPEN = String(process.env.ALLOW_REGISTRATION ?? 'true') !== 'false';

router.post(
  '/register',
  asyncHandler(async (req, res) => {
    if (!REGISTRATION_OPEN) {
      throw new ApiError(
        403,
        'This server is not accepting new sign-ups. Ask your flat Admin to create your account.'
      );
    }

    const name = requireString(req.body, 'name', { max: 120 });
    const email = requireEmail(req.body);
    const phone = optionalString(req.body, 'phone', { max: 20 });
    const password = requirePassword(req.body);
    const groupName = optionalString(req.body, 'groupName', { max: 120 });
    // Asking to join an existing flat instead of starting one.
    const joinGroupId = optionalId(req.body, 'joinGroupId');
    const message = optionalString(req.body, 'message', { max: 255 });

    if (groupName && joinGroupId) {
      throw new ApiError(400, 'Either start a new flat or join one, not both');
    }

    // Section 16: passwords are only ever stored as a hash.
    const passwordHash = await bcrypt.hash(password, 10);

    const result = await withTransaction(async (conn) => {
      const [existing] = await conn.query('SELECT id FROM users WHERE email = ?', [email]);
      if (existing.length > 0) {
        throw new ApiError(409, 'An account with that email already exists');
      }

      // Checked before the account is created, so a bad flat id does not leave
      // an orphan user behind.
      let joinTarget = null;
      if (joinGroupId) {
        const [g] = await conn.query('SELECT id, name FROM `groups` WHERE id = ?', [joinGroupId]);
        if (g.length === 0) throw new ApiError(404, 'That flat does not exist');
        joinTarget = g[0];
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
        await seedCategories(conn, groupId);

        group = { id: groupId, name: groupName, adminId: userId, isAdmin: true };
      }

      // Joining is a request, not a membership. Nothing is added to
      // group_members until the Admin approves, so the account exists but sees
      // nothing at all in the meantime.
      let pending = null;
      if (joinTarget) {
        await conn.query(
          `INSERT INTO join_requests (group_id, user_id, message) VALUES (?, ?, ?)`,
          [joinTarget.id, userId, message]
        );
        pending = { groupId: Number(joinTarget.id), groupName: joinTarget.name };
      }

      const [rows] = await conn.query(
        'SELECT id, name, email, phone, role, status FROM users WHERE id = ?',
        [userId]
      );
      return { user: rows[0], group, pending };
    });

    res.status(201).json({
      token: signToken(result.user),
      user: publicUser(result.user),
      group: result.group,
      // Set when they asked to join rather than starting a flat. The app shows
      // a waiting screen on this rather than dropping them into an empty app.
      pendingJoin: result.pending
    });
  })
);

/**
 * GET /api/auth/flats?q=sunrise
 *
 * Finds a flat by name so someone signing up can pick the one they mean.
 *
 * Requires a search term of at least three characters and never lists
 * everything: the point is to confirm the flat you were told about, not to
 * browse who else uses this server. Returns the id and name only — no member
 * count, no Admin name, nothing about the people in it. Being listed here
 * grants nothing, since joining still needs the Admin to approve.
 */
router.get(
  '/flats',
  asyncHandler(async (req, res) => {
    const term = String(req.query.q || '').trim();
    if (term.length < 3) {
      throw new ApiError(400, 'Type at least three letters of the flat name');
    }
    // A literal % or _ in a flat name must not act as a LIKE wildcard.
    const escaped = term.replace(/[\\%_]/g, (ch) => `\\${ch}`);
    const [rows] = await pool.query(
      'SELECT id, name FROM `groups` WHERE name LIKE ? ORDER BY name LIMIT 10',
      [`%${escaped}%`]
    );
    res.json({ flats: rows.map((r) => ({ id: Number(r.id), name: r.name })) });
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

    // Someone whose request has not been decided yet belongs to no group, so
    // without this the app would show them an empty shell with no explanation.
    const [pending] = await pool.query(
      `SELECT jr.group_id, jr.status, g.name
         FROM join_requests jr
         JOIN \`groups\` g ON g.id = jr.group_id
        WHERE jr.user_id = ? AND jr.status IN ('pending', 'declined')
        ORDER BY jr.created_at DESC
        LIMIT 1`,
      [req.user.id]
    );

    res.json({
      user: publicUser(req.user),
      groups: groups.map((g) => ({
        id: Number(g.id),
        name: g.name,
        adminId: Number(g.admin_id),
        isAdmin: Number(g.admin_id) === Number(req.user.id)
      })),
      pendingJoin: groups.length === 0 && pending.length > 0
        ? {
            groupId: Number(pending[0].group_id),
            groupName: pending[0].name,
            status: pending[0].status
          }
        : null
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
