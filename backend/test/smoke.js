'use strict';

/**
 * End-to-end check of the rules the spec is strictest about. Run against a
 * seeded database with the API up:  npm run smoke
 */

const BASE = process.env.API_BASE || 'http://localhost:4000';

let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed += 1;
    console.log(`  PASS  ${name}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

async function call(method, path, { token, body } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }
  return { status: res.status, body: json };
}

async function login(email) {
  const res = await call('POST', '/api/auth/login', {
    body: { email, password: 'password123' }
  });
  if (res.status !== 200) throw new Error(`login failed for ${email}: ${JSON.stringify(res.body)}`);
  return res.body.token;
}

async function main() {
  console.log('\nFlat Common Expense Manager — smoke test\n');

  // --- auth -----------------------------------------------------------------
  console.log('Authentication');
  const adminToken = await login('rahul@flat302.test');
  const gautamToken = await login('gautam@flat302.test');
  const priyaToken = await login('priya@flat302.test');

  const badLogin = await call('POST', '/api/auth/login', {
    body: { email: 'rahul@flat302.test', password: 'wrongpassword' }
  });
  check('wrong password is rejected', badLogin.status === 401);

  const noToken = await call('GET', '/api/groups/1/dashboard');
  check('unauthenticated request is rejected', noToken.status === 401);

  const me = await call('GET', '/api/auth/me', { token: adminToken });
  check('me returns the group', me.body.groups.length === 1);
  check('admin flag is set for Rahul', me.body.groups[0].isAdmin === true);
  const groupId = me.body.groups[0].id;

  const gautamMe = await call('GET', '/api/auth/me', { token: gautamToken });
  check('Gautam is not admin', gautamMe.body.groups[0].isAdmin === false);

  // --- membership boundary --------------------------------------------------
  console.log('\nGroup isolation');
  const outsider = await call('POST', '/api/auth/register', {
    body: {
      name: 'Outsider',
      email: `outsider${Date.now()}@example.test`,
      password: 'password123',
      groupName: 'Some other flat'
    }
  });
  check('outsider can register their own flat', outsider.status === 201);
  const outsiderToken = outsider.body.token;

  const crossGroup = await call('GET', `/api/groups/${groupId}/dashboard`, {
    token: outsiderToken
  });
  check('outsider cannot read another flat dashboard', crossGroup.status === 403);

  // --- add expense ----------------------------------------------------------
  console.log('\nAdd expense (section 5, Table 3)');
  const cats = await call('GET', `/api/groups/${groupId}/categories`, { token: gautamToken });
  check('categories seeded from section 8', cats.body.categories.length === 12);
  const grocery = cats.body.categories.find((c) => c.name === 'Grocery');

  const created = await call('POST', `/api/groups/${groupId}/expenses`, {
    token: gautamToken,
    body: {
      categoryId: grocery.id,
      description: 'Monthly groceries',
      amount: 1000,
      paidBy: gautamMe.body.user.id
      // splitTo deliberately omitted — must default to the Admin
    }
  });
  check('expense created', created.status === 201);
  const expense = created.body.expense;
  check('status starts Pending', expense.status === 'pending');
  check('splitTo defaults to the Admin', expense.splitTo.id === me.body.user.id, JSON.stringify(expense.splitTo));
  check('splitTo is Rahul by name', expense.splitTo.name === 'Rahul');
  check('paidBy is Gautam', expense.paidBy.name === 'Gautam');
  check('full amount stored, not a 200 share', expense.amount === '1000.00', expense.amount);

  const zero = await call('POST', `/api/groups/${groupId}/expenses`, {
    token: gautamToken,
    body: { categoryId: grocery.id, description: 'Free stuff', amount: 0 }
  });
  check('amount of zero is rejected', zero.status === 400);

  const negative = await call('POST', `/api/groups/${groupId}/expenses`, {
    token: gautamToken,
    body: { categoryId: grocery.id, description: 'Negative', amount: -50 }
  });
  check('negative amount is rejected', negative.status === 400);

  const noDescription = await call('POST', `/api/groups/${groupId}/expenses`, {
    token: gautamToken,
    body: { categoryId: grocery.id, amount: 100 }
  });
  check('description is mandatory', noDescription.status === 400);

  const badSplit = await call('POST', `/api/groups/${groupId}/expenses`, {
    token: gautamToken,
    body: {
      categoryId: grocery.id,
      description: 'Bad split',
      amount: 100,
      splitTo: outsider.body.user.id
    }
  });
  check('splitTo must be a group member', badSplit.status === 400);

  // --- approval workflow ----------------------------------------------------
  console.log('\nApproval workflow (section 7)');
  const memberApprove = await call('POST', `/api/expenses/${expense.id}/approve`, {
    token: gautamToken
  });
  check('a member cannot approve', memberApprove.status === 403);

  const otherMemberApprove = await call('POST', `/api/expenses/${expense.id}/approve`, {
    token: priyaToken
  });
  check('another member cannot approve either', otherMemberApprove.status === 403);

  const approved = await call('POST', `/api/expenses/${expense.id}/approve`, {
    token: adminToken
  });
  check('Admin can approve', approved.status === 200);
  check('status becomes Approved', approved.body.expense.status === 'approved');
  check('approvedBy recorded', approved.body.expense.approvedBy.name === 'Rahul');

  const doubleApprove = await call('POST', `/api/expenses/${expense.id}/approve`, {
    token: adminToken
  });
  check('cannot approve twice', doubleApprove.status === 409);

  const rejectNoReason = await call('POST', `/api/expenses/${expense.id}/reject`, {
    token: adminToken
  });
  check('rejection requires a reason', rejectNoReason.status === 400);

  // --- edit-after-approval --------------------------------------------------
  console.log('\nAudit trail (section 15)');
  const edited = await call('PATCH', `/api/expenses/${expense.id}`, {
    token: gautamToken,
    body: { amount: 1250 }
  });
  check('author can edit', edited.status === 200);
  check('editing an approved expense reopens it', edited.body.expense.status === 'pending');
  check('approvedBy cleared on reopen', edited.body.expense.approvedBy === null);

  const details = await call('GET', `/api/expenses/${expense.id}`, { token: adminToken });
  const actions = details.body.audit.map((a) => a.action);
  check('audit records created', actions.includes('created'));
  check('audit records approval', actions.includes('approved'));
  check('audit records the reopening edit', actions.includes('edited_reopened'));

  const strangerEdit = await call('PATCH', `/api/expenses/${expense.id}`, {
    token: priyaToken,
    body: { amount: 5 }
  });
  check('an unrelated member cannot edit', strangerEdit.status === 403);

  const hiddenExpense = await call('GET', `/api/expenses/${expense.id}`, {
    token: outsiderToken
  });
  check('outsider gets 404, not 403, for a foreign expense', hiddenExpense.status === 404);

  // --- totals ---------------------------------------------------------------
  console.log('\nTotals exclude non-approved (section 3)');
  const dash = await call('GET', `/api/groups/${groupId}/dashboard`, { token: adminToken });
  check('dashboard loads', dash.status === 200);

  const listed = await call('GET', `/api/groups/${groupId}/expenses?status=rejected`, {
    token: adminToken
  });
  const rejectedTotal = listed.body.expenses.reduce((s, e) => s + Number(e.amount), 0);
  check('a rejected expense exists in history', listed.body.expenses.length > 0);

  const report = await call('GET', `/api/groups/${groupId}/reports/monthly`, {
    token: adminToken
  });
  check('monthly report loads', report.status === 200);
  check(
    'rejected total is reported separately',
    Number(report.body.expenseTotals.byStatus.rejected) === rejectedTotal
  );

  const approvedFromCategories = report.body.byCategory.reduce((s, c) => s + Number(c.total), 0);
  check(
    'category totals sum to the approved total',
    Math.abs(approvedFromCategories - Number(report.body.expenseTotals.byStatus.approved)) < 0.005,
    `${approvedFromCategories} vs ${report.body.expenseTotals.byStatus.approved}`
  );

  const splitToTotal = report.body.bySplitTo.reduce((s, r) => s + Number(r.total), 0);
  check(
    'split-to totals also sum to the approved total',
    Math.abs(splitToTotal - Number(report.body.expenseTotals.byStatus.approved)) < 0.005
  );

  // --- contributions --------------------------------------------------------
  console.log('\nContributions (section 4)');
  const contributions = await call('GET', `/api/groups/${groupId}/contributions`, {
    token: adminToken
  });
  check('all five members listed', contributions.body.contributions.length === 5);
  check('expected total is 25,000', contributions.body.totals.expected === '25000.00');

  const memberRecords = await call('POST', `/api/groups/${groupId}/contributions`, {
    token: priyaToken,
    body: { userId: 2, paidAmount: 5000 }
  });
  check('a member cannot record contributions', memberRecords.status === 403);

  const priyaId = contributions.body.contributions.find((c) => c.name === 'Priya').userId;
  const recorded = await call('POST', `/api/groups/${groupId}/contributions`, {
    token: adminToken,
    body: { userId: priyaId, paidAmount: 5000 }
  });
  check('Admin can record a contribution', recorded.status === 201);
  check('status flips to paid', recorded.body.contribution.status === 'paid');

  const partial = await call('POST', `/api/groups/${groupId}/contributions`, {
    token: adminToken,
    body: { userId: priyaId, paidAmount: 2000 }
  });
  check('a short payment is marked partial', partial.body.contribution.status === 'partial');

  // --- member cap -----------------------------------------------------------
  console.log('\nGroup size rule (section 3)');
  const sixth = await call('POST', `/api/groups/${groupId}/members`, {
    token: adminToken,
    body: {
      name: 'Sixth Person',
      email: `sixth${Date.now()}@flat302.test`,
      password: 'password123'
    }
  });
  check('a sixth member is refused', sixth.status === 409, JSON.stringify(sixth.body));

  console.log(`\n${passed} passed, ${failed} failed\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('\nSmoke test crashed:', err);
  process.exit(1);
});
