'use strict';

/**
 * Month closing. A closed month is settled: its expenses and its contributions
 * are read-only, so last month's totals cannot drift after everyone has agreed
 * them.
 *
 * Closing is reversible — the Admin reopens, corrects, and closes again — and
 * every open and close is written to group_audit, so a sealed month that was
 * later touched says so.
 */

const { ApiError } = require('../middleware/errors');

/** The first day of the month after the given first-of-month date. */
function nextMonthOf(month) {
  const [year, mon] = month.split('-').map(Number);
  const y = mon === 12 ? year + 1 : year;
  const m = mon === 12 ? 1 : mon + 1;
  return `${y}-${String(m).padStart(2, '0')}-01`;
}

/** The month a YYYY-MM-DD date falls in, as its first day. */
function monthOfDate(date) {
  return `${String(date).slice(0, 7)}-01`;
}

async function isMonthClosed(conn, groupId, month) {
  const [rows] = await conn.query(
    'SELECT id FROM month_closures WHERE group_id = ? AND month = ?',
    [groupId, month]
  );
  return rows.length > 0;
}

/**
 * Guard for anything that writes into a month. Throws 409 rather than 403:
 * the caller has the right to do this, the period simply is not open.
 */
async function assertMonthOpen(conn, groupId, month, what = 'This') {
  if (await isMonthClosed(conn, groupId, month)) {
    const label = String(month).slice(0, 7);
    throw new ApiError(
      409,
      `${what} belongs to ${label}, which the Admin has closed. Reopen that month to change it.`
    );
  }
}

async function writeGroupAudit(conn, { groupId, action, detail, subjectId, actorId }) {
  await conn.query(
    `INSERT INTO group_audit (group_id, action, detail, subject_id, actor_id)
     VALUES (?, ?, ?, ?, ?)`,
    [groupId, action, detail || null, subjectId || null, actorId]
  );
}

/**
 * Moves expenses still awaiting a decision into the following month so that
 * closing does not strand them, and so the closed month contains only settled
 * figures.
 *
 * This rewrites expense_date, which is a claim about when the money was spent,
 * so the original date is written to expense_audit. The record of what
 * actually happened survives even though the working date moves.
 */
async function carryOverPending(conn, groupId, month, actorId) {
  const target = nextMonthOf(month);

  const [pending] = await conn.query(
    `SELECT id, expense_date FROM expenses
      WHERE group_id = ?
        AND status = 'pending'
        AND expense_date >= ?
        AND expense_date < ?`,
    [groupId, month, target]
  );

  for (const row of pending) {
    await conn.query('UPDATE expenses SET expense_date = ? WHERE id = ?', [target, row.id]);
    await conn.query(
      `INSERT INTO expense_audit (expense_id, action, from_status, to_status, detail, actor_id)
       VALUES (?, 'carried_over', 'pending', 'pending', ?, ?)`,
      [
        row.id,
        `Undecided when ${String(month).slice(0, 7)} closed; moved from ${row.expense_date} to ${target}`,
        actorId
      ]
    );
  }

  return pending.length;
}

module.exports = {
  nextMonthOf,
  monthOfDate,
  isMonthClosed,
  assertMonthOpen,
  writeGroupAudit,
  carryOverPending
};
