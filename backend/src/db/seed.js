'use strict';

/**
 * Seeds the worked example from section 20 of the spec:
 * Rahul is Admin, five members, everyone's September contribution is Rs 5,000,
 * and Gautam's Rs 1,000 grocery expense sits Pending with Split To = Rahul.
 *
 * Safe to re-run: it clears the demo group first.
 */

require('dotenv').config();

const bcrypt = require('bcryptjs');
const { pool, withTransaction } = require('./pool');
const { DEFAULT_CATEGORIES } = require('../services/categories');

const GROUP_NAME = 'Flat 302, Sunrise Apartments';
const PASSWORD = 'password123';

const PEOPLE = [
  { name: 'Rahul', email: 'rahul@flat302.test', phone: '9000000001', admin: true },
  { name: 'Gautam', email: 'gautam@flat302.test', phone: '9000000002' },
  { name: 'Priya', email: 'priya@flat302.test', phone: '9000000003' },
  { name: 'Anjali', email: 'anjali@flat302.test', phone: '9000000004' },
  { name: 'Vikram', email: 'vikram@flat302.test', phone: '9000000005' }
];

function monthOf(date) {
  return `${date.toISOString().slice(0, 7)}-01`;
}

async function main() {
  const passwordHash = await bcrypt.hash(PASSWORD, 10);
  const month = monthOf(new Date());
  const today = new Date().toISOString().slice(0, 10);

  await withTransaction(async (conn) => {
    // Clear any previous run of this seed, leaving real groups alone.
    const [old] = await conn.query('SELECT id FROM `groups` WHERE name = ?', [GROUP_NAME]);
    if (old.length > 0) {
      const groupId = old[0].id;
      await conn.query('DELETE FROM expenses WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM monthly_contributions WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM month_closures WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM group_audit WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM categories WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM group_members WHERE group_id = ?', [groupId]);
      await conn.query('DELETE FROM `groups` WHERE id = ?', [groupId]);
    }
    await conn.query(
      'DELETE FROM users WHERE email IN (?)',
      [PEOPLE.map((p) => p.email)]
    );

    // People
    const ids = {};
    for (const person of PEOPLE) {
      const [result] = await conn.query(
        `INSERT INTO users (name, email, phone, password_hash, role)
         VALUES (?, ?, ?, ?, ?)`,
        [person.name, person.email, person.phone, passwordHash, person.admin ? 'admin' : 'member']
      );
      ids[person.name] = result.insertId;
    }

    // Group, with Rahul as the central account holder
    const [groupResult] = await conn.query(
      'INSERT INTO `groups` (name, admin_id) VALUES (?, ?)',
      [GROUP_NAME, ids.Rahul]
    );
    const groupId = groupResult.insertId;

    for (const person of PEOPLE) {
      await conn.query('INSERT INTO group_members (group_id, user_id) VALUES (?, ?)', [
        groupId,
        ids[person.name]
      ]);
    }

    // Categories from section 8
    await conn.query('INSERT INTO categories (group_id, name, icon, sort_order) VALUES ?', [
      DEFAULT_CATEGORIES.map((c, i) => [groupId, c.name, c.icon, i])
    ]);
    const [categories] = await conn.query(
      'SELECT id, name FROM categories WHERE group_id = ?',
      [groupId]
    );
    const categoryId = (name) => categories.find((c) => c.name === name).id;

    // Table 2: everyone owes 5,000; three have paid.
    const paidBy = { Rahul: true, Gautam: true, Anjali: true };
    for (const person of PEOPLE) {
      const hasPaid = Boolean(paidBy[person.name]);
      await conn.query(
        `INSERT INTO monthly_contributions
           (group_id, user_id, month, expected_amount, paid_amount, status, paid_at, recorded_by)
         VALUES (?, ?, ?, '5000.00', ?, ?, ?, ?)`,
        [
          groupId,
          ids[person.name],
          month,
          hasPaid ? '5000.00' : '0.00',
          hasPaid ? 'paid' : 'pending',
          hasPaid ? new Date() : null,
          ids.Rahul
        ]
      );
    }

    // Section 6's example expense, plus a couple of others for the dashboard.
    const expenses = [
      {
        category: 'Grocery',
        description: 'Monthly groceries',
        amount: '1000.00',
        paid: 'Gautam',
        split: 'Rahul',
        status: 'pending'
      },
      {
        category: 'Electricity',
        description: 'September electricity bill',
        amount: '2450.00',
        paid: 'Rahul',
        split: 'Rahul',
        status: 'approved'
      },
      {
        category: 'Internet',
        description: 'Broadband renewal',
        amount: '1199.00',
        paid: 'Priya',
        split: 'Rahul',
        status: 'approved'
      },
      {
        category: 'Milk',
        description: 'Milk for the week',
        amount: '420.00',
        paid: 'Anjali',
        split: 'Rahul',
        status: 'pending'
      },
      {
        category: 'Other',
        description: 'Personal snacks — not a flat expense',
        amount: '300.00',
        paid: 'Vikram',
        split: 'Rahul',
        status: 'rejected',
        reason: 'Personal purchase, not a shared flat expense'
      }
    ];

    for (const e of expenses) {
      const decided = e.status === 'approved' || e.status === 'rejected';
      const [result] = await conn.query(
        `INSERT INTO expenses
           (group_id, category_id, description, amount, paid_by, split_to, expense_date,
            status, created_by, approved_by, approved_at, rejection_reason)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          groupId,
          categoryId(e.category),
          e.description,
          e.amount,
          ids[e.paid],
          ids[e.split],
          today,
          e.status,
          ids[e.paid],
          decided ? ids.Rahul : null,
          decided ? new Date() : null,
          e.reason || null
        ]
      );
      await conn.query(
        `INSERT INTO expense_audit (expense_id, action, to_status, actor_id)
         VALUES (?, 'created', 'pending', ?)`,
        [result.insertId, ids[e.paid]]
      );
      if (decided) {
        await conn.query(
          `INSERT INTO expense_audit (expense_id, action, from_status, to_status, detail, actor_id)
           VALUES (?, ?, 'pending', ?, ?, ?)`,
          [result.insertId, e.status, e.status, e.reason || null, ids.Rahul]
        );
      }
    }

    // Five months of settled history. Without it the trend and comparison
    // charts open empty on a fresh install, which makes them look broken
    // rather than new.
    const history = [
      { back: 5, rows: [['Rent', 'Rent', '12000.00', 'Rahul'], ['Grocery', 'Groceries', '4100.00', 'Gautam'], ['Electricity', 'Electricity', '1980.00', 'Rahul'], ['Milk', 'Milk', '1250.00', 'Anjali']] },
      { back: 4, rows: [['Rent', 'Rent', '12000.00', 'Rahul'], ['Grocery', 'Groceries', '3750.00', 'Priya'], ['Electricity', 'Electricity', '2260.00', 'Rahul'], ['Internet', 'Broadband', '1199.00', 'Priya']] },
      { back: 3, rows: [['Rent', 'Rent', '12000.00', 'Rahul'], ['Grocery', 'Groceries', '4980.00', 'Gautam'], ['Gas', 'Gas cylinder', '1150.00', 'Vikram'], ['Cleaning', 'Cleaner', '1800.00', 'Anjali']] },
      { back: 2, rows: [['Rent', 'Rent', '12000.00', 'Rahul'], ['Grocery', 'Groceries', '3420.00', 'Anjali'], ['Electricity', 'Electricity', '3100.00', 'Rahul'], ['Water', 'Water tanker', '900.00', 'Vikram']] },
      { back: 1, rows: [['Rent', 'Rent', '12000.00', 'Rahul'], ['Grocery', 'Groceries', '5240.00', 'Gautam'], ['Internet', 'Broadband', '1199.00', 'Priya'], ['Maintenance', 'Plumber', '2300.00', 'Rahul']] }
    ];

    for (const { back, rows } of history) {
      const d = new Date();
      d.setDate(1);
      d.setMonth(d.getMonth() - back);
      // Built from local parts, not toISOString(): east of UTC the first of
      // the month converts back to the last day of the previous one, which
      // would file every historical row a month early.
      const histMonth =
        `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
      const histDate = `${histMonth.slice(0, 8)}12`;

      for (const person of PEOPLE) {
        await conn.query(
          `INSERT INTO monthly_contributions
             (group_id, user_id, month, expected_amount, paid_amount, status, paid_at, recorded_by)
           VALUES (?, ?, ?, '5000.00', '5000.00', 'paid', ?, ?)`,
          [groupId, ids[person.name], histMonth, d, ids.Rahul]
        );
      }

      for (const [category, description, amount, payer] of rows) {
        const [result] = await conn.query(
          `INSERT INTO expenses
             (group_id, category_id, description, amount, paid_by, split_to, expense_date,
              status, created_by, approved_by, approved_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, 'approved', ?, ?, ?)`,
          [groupId, categoryId(category), description, amount, ids[payer], ids.Rahul,
           histDate, ids[payer], ids.Rahul, d]
        );
        await conn.query(
          `INSERT INTO expense_audit (expense_id, action, to_status, actor_id)
           VALUES (?, 'created', 'pending', ?)`,
          [result.insertId, ids[payer]]
        );
        await conn.query(
          `INSERT INTO expense_audit (expense_id, action, from_status, to_status, actor_id)
           VALUES (?, 'approved', 'pending', 'approved', ?)`,
          [result.insertId, ids.Rahul]
        );
      }
    }

    console.log(`Seeded group ${groupId} "${GROUP_NAME}" with ${PEOPLE.length} members.`);
    console.log(`Plus ${history.length} months of settled history, so the charts have something to show.`);
  });

  console.log('\nSign in with any of:');
  for (const p of PEOPLE) {
    console.log(`  ${p.email.padEnd(24)} / ${PASSWORD}${p.admin ? '   (Admin)' : ''}`);
  }

  await pool.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
