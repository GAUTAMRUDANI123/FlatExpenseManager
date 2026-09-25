'use strict';

const jwt = require('jsonwebtoken');
const { pool } = require('../db/pool');
const { ApiError } = require('./errors');

const JWT_SECRET = process.env.JWT_SECRET || 'dev-only-insecure-secret';
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d';

function signToken(user) {
  return jwt.sign(
    { sub: String(user.id), email: user.email, name: user.name },
    JWT_SECRET,
    { expiresIn: JWT_EXPIRES_IN }
  );
}

/** Verifies the bearer token and loads the user onto req.user. */
async function authenticate(req, _res, next) {
  try {
    const header = req.headers.authorization || '';
    if (!header.startsWith('Bearer ')) {
      throw new ApiError(401, 'Missing bearer token');
    }

    let payload;
    try {
      payload = jwt.verify(header.slice(7), JWT_SECRET);
    } catch (err) {
      throw new ApiError(401, 'Invalid or expired token');
    }

    const [rows] = await pool.query(
      'SELECT id, name, email, phone, role, status FROM users WHERE id = ?',
      [payload.sub]
    );
    if (rows.length === 0) throw new ApiError(401, 'User no longer exists');
    if (rows[0].status !== 'active') throw new ApiError(403, 'Account is inactive');

    req.user = rows[0];
    next();
  } catch (err) {
    next(err);
  }
}

/**
 * Section 15: "Users can access only data belonging to their group."
 * Resolves :groupId, confirms membership, and records whether this user is the
 * group's Admin. Admin-ness is a property of the group (groups.admin_id), not
 * a global flag, so it is decided here and nowhere else.
 */
async function requireGroupMember(req, _res, next) {
  try {
    const groupId = Number(req.params.groupId);
    if (!Number.isInteger(groupId) || groupId <= 0) {
      throw new ApiError(400, 'Invalid group id');
    }

    const [rows] = await pool.query(
      `SELECT g.id, g.name, g.admin_id, gm.status AS member_status
         FROM \`groups\` g
         JOIN group_members gm ON gm.group_id = g.id AND gm.user_id = ?
        WHERE g.id = ?`,
      [req.user.id, groupId]
    );

    if (rows.length === 0) throw new ApiError(403, 'You are not a member of this group');
    if (rows[0].member_status !== 'active') {
      throw new ApiError(403, 'Your membership in this group is inactive');
    }

    req.group = { id: rows[0].id, name: rows[0].name, adminId: Number(rows[0].admin_id) };
    req.isGroupAdmin = req.group.adminId === Number(req.user.id);
    next();
  } catch (err) {
    next(err);
  }
}

/**
 * Section 16: "Enforce Admin-only operations on the backend, not only in the
 * UI." Every approve/reject/admin route sits behind this.
 */
function requireGroupAdmin(req, _res, next) {
  if (!req.isGroupAdmin) {
    return next(new ApiError(403, 'Only the group Admin can do this'));
  }
  next();
}

module.exports = {
  signToken,
  authenticate,
  requireGroupMember,
  requireGroupAdmin,
  JWT_SECRET
};
