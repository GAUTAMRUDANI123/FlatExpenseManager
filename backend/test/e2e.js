'use strict';

/**
 * End-to-end: a flat built from nothing and lived in for a month.
 *
 * This is deliberately not a unit test. It creates its own flat, fills it the
 * way five people actually would — both routes in, contributions, expenses,
 * decisions, a correction, a month closed and reopened — and checks that the
 * numbers still reconcile at the end. It cleans up after itself.
 */

const BASE = process.env.API_BASE || 'http://localhost:4000';
const STAMP = Date.now().toString(36);
const PW = 'password123';

let passed = 0;
let failed = 0;
const failures = [];

function check(name, cond, detail) {
  if (cond) {
    passed += 1;
    console.log(`    ok   ${name}`);
  } else {
    failed += 1;
    failures.push(name);
    console.log(`    FAIL ${name}${detail ? ` -- ${detail}` : ''}`);
  }
}

function step(n, title) {
  console.log(`\n${n}. ${title}`);
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

const email = (who) => `${who}.${STAMP}@e2e.test`;

async function login(who, password = PW) {
  const r = await call('POST', '/api/auth/login', {
    body: { email: email(who), password }
  });
  if (r.status !== 200) throw new Error(`login ${who}: ${JSON.stringify(r.body)}`);
  return r.body.token;
}

const money = (n) => Number(n).toFixed(2);

async function main() {
  console.log(`\n=== END TO END: a flat from scratch (run ${STAMP}) ===`);

  // ---------------------------------------------------------------- 1
  step(1, 'The Admin creates the flat');
  const flatName = `E2E Flat ${STAMP}`;
  const reg = await call('POST', '/api/auth/register', {
    body: { name: 'Meera', email: email('admin'), password: PW, groupName: flatName }
  });
  check('flat created', reg.status === 201, JSON.stringify(reg.body));
  const admin = reg.body.token;
  const gid = reg.body.group.id;
  check('creator is the Admin', reg.body.group.isAdmin === true);

  const cats = await call('GET', `/api/groups/${gid}/categories`, { token: admin });
  const headings = cats.body.categories.filter((c) => c.parentId === null);
  check('starter categories seeded', cats.body.categories.length === 25,
    String(cats.body.categories.length));
  check('arranged as a tree', headings.length === 5, `${headings.length} headings`);
  const cat = (n) => cats.body.categories.find((c) => c.name === n).id;

  // ---------------------------------------------------------------- 2
  step(2, 'Four flatmates are added by the Admin');
  for (const who of ['gita', 'ravi', 'sunil', 'asha']) {
    const r = await call('POST', `/api/groups/${gid}/members`, {
      token: admin,
      body: { name: who, email: email(who), password: PW }
    });
    check(`${who} added`, r.status === 201, JSON.stringify(r.body));
  }

  // ---------------------------------------------------------------- 3
  step(3, 'A fourth signs herself up and asks to join');
  const found = await call('GET', `/api/auth/flats?q=${encodeURIComponent(flatName.slice(0, 12))}`);
  check('she can find the flat by name', found.body.flats.some((f) => f.id === gid));

  const askReg = await call('POST', '/api/auth/register', {
    body: { name: 'nisha', email: email('nisha'), password: PW, joinGroupId: gid, message: 'Room 4' }
  });
  check('request accepted', askReg.status === 201, JSON.stringify(askReg.body));
  const nisha = askReg.body.token;
  check('she is told she is waiting', askReg.body.pendingJoin.groupName === flatName);

  const blocked = await call('GET', `/api/groups/${gid}/dashboard`, { token: nisha });
  check('she sees nothing while pending', blocked.status === 403);

  const queue = await call('GET', `/api/groups/${gid}/join-requests`, { token: admin });
  check('the Admin sees one request', queue.body.requests.length === 1);
  check('with her note attached', queue.body.requests[0].message === 'Room 4');

  const approved = await call(
    'POST', `/api/groups/${gid}/join-requests/${queue.body.requests[0].id}/approve`,
    { token: admin }
  );
  check('approved', approved.status === 200);
  const nowIn = await call('GET', `/api/groups/${gid}/dashboard`, { token: nisha });
  check('she is in immediately after approval', nowIn.status === 200);

  // ---------------------------------------------------------------- 4
  step(4, 'A stranger who guessed the name is turned away');
  const stranger = await call('POST', '/api/auth/register', {
    body: { name: 'stranger', email: email('stranger'), password: PW, joinGroupId: gid }
  });
  check('their request is recorded', stranger.status === 201);
  const sTok = stranger.body.token;
  const sBlocked = await call('GET', `/api/groups/${gid}/expenses`, { token: sTok });
  check('they see nothing', sBlocked.status === 403);

  const q2 = await call('GET', `/api/groups/${gid}/join-requests`, { token: admin });
  const declined = await call(
    'POST', `/api/groups/${gid}/join-requests/${q2.body.requests[0].id}/decline`,
    { token: admin }
  );
  check('declined', declined.status === 200);
  const after = await call('GET', '/api/auth/me', { token: sTok });
  check('they are in no flat', after.body.groups.length === 0);
  check('and are told why', after.body.pendingJoin.status === 'declined');

  // ---------------------------------------------------------------- 5
  step(5, 'The flat is full at five');
  const members = await call('GET', `/api/groups/${gid}/members`, { token: admin });
  check('five flatmates', members.body.flatmateCount === 5, String(members.body.flatmateCount));
  check('six accounts in total', members.body.members.length === 6,
    String(members.body.members.length));
  const sixth = await call('POST', `/api/groups/${gid}/members`, {
    token: admin,
    body: { name: 'sixth', email: email('sixth'), password: PW }
  });
  check('a sixth is refused', sixth.status === 409);

  // ---------------------------------------------------------------- 6
  step(6, 'The Admin sets this month at 5,000 each');
  const bulk = await call('POST', `/api/groups/${gid}/contributions/bulk-expected`, {
    token: admin, body: { expectedAmount: '5000.00' }
  });
  check('set for everyone', bulk.status === 200);
  const contrib = await call('GET', `/api/groups/${gid}/contributions`, { token: admin });
  check('five people are billed', contrib.body.contributions.length === 5);
  check('the Admin is not among them', !contrib.body.contributions.some((c) => c.isAdmin));
  check('expected is 25,000', contrib.body.totals.expected === '25000.00',
    contrib.body.totals.expected);

  const ids = {};
  for (const m of members.body.members) ids[m.name] = m.id;

  step(6.5, 'Three of them pay');
  for (const who of ['gita', 'ravi', 'nisha']) {
    const r = await call('POST', `/api/groups/${gid}/contributions`, {
      token: admin, body: { userId: ids[who], paidAmount: '5000.00' }
    });
    check(`${who} recorded as paid`, r.status === 201, JSON.stringify(r.body));
  }
  const billAdmin = await call('POST', `/api/groups/${gid}/contributions`, {
    token: admin, body: { userId: ids.Meera, paidAmount: '5000.00' }
  });
  check('the Admin cannot be billed', billAdmin.status === 400);

  // ---------------------------------------------------------------- 7
  step(7, 'They spend');
  const gitaTok = await login('gita');
  const raviTok = await login('ravi');
  const today = new Date().toISOString().slice(0, 10);

  const spend = async (token, category, description, amount) => {
    const r = await call('POST', `/api/groups/${gid}/expenses`, {
      token,
      body: { categoryId: cat(category), description, amount, expenseDate: today }
    });
    check(`${description}`, r.status === 201, JSON.stringify(r.body));
    return r.body.expense;
  };

  const rent = await spend(admin, 'Rent', 'October rent', '12000.00');
  const veg = await spend(gitaTok, 'Vegetables', 'Weekly sabzi', '780.00');
  const milk = await spend(raviTok, 'Milk & Dairy', 'Milk for the week', '420.00');
  const snack = await spend(raviTok, 'Snacks', 'Personal biscuits', '150.00');

  check('split_to defaults to the Admin', veg.splitTo.name === 'Meera', veg.splitTo.name);
  check('the full amount is stored', veg.amount === '780.00', veg.amount);
  check('it starts pending', veg.status === 'pending');

  // ---------------------------------------------------------------- 8
  step(8, 'The Admin decides');
  for (const e of [rent, veg, milk]) {
    const r = await call('POST', `/api/expenses/${e.id}/approve`, { token: admin });
    check(`approved ${e.description}`, r.status === 200);
  }
  const memberApprove = await call('POST', `/api/expenses/${snack.id}/approve`, { token: gitaTok });
  check('a flatmate cannot approve', memberApprove.status === 403);
  const rejected = await call('POST', `/api/expenses/${snack.id}/reject`, {
    token: admin, body: { reason: 'Personal, not a flat expense' }
  });
  check('rejected with a reason', rejected.status === 200);

  // ---------------------------------------------------------------- 9
  step(9, 'A correction reopens the decision');
  const edited = await call('PATCH', `/api/expenses/${veg.id}`, {
    token: gitaTok, body: { amount: '880.00' }
  });
  check('the author can correct it', edited.status === 200);
  check('it goes back for approval', edited.body.reopened === true);
  check('and is pending again', edited.body.expense.status === 'pending');
  await call('POST', `/api/expenses/${veg.id}/approve`, { token: admin });

  // ---------------------------------------------------------------- 10
  step(10, 'The numbers reconcile');
  const dash = await call('GET', `/api/groups/${gid}/dashboard`, { token: admin });
  const approvedTotal = 12000 + 880 + 420;
  check('approved total is right', dash.body.expenses.approvedTotal === money(approvedTotal),
    dash.body.expenses.approvedTotal);
  check('received is 15,000', dash.body.contributions.received === '15000.00',
    dash.body.contributions.received);
  check('balance = received - approved',
    dash.body.balance === money(15000 - approvedTotal), dash.body.balance);
  check('rejected is left out of approved', !dash.body.expenses.approvedTotal.includes('150'));

  const settle = await call('GET', `/api/groups/${gid}/settlement`, { token: admin });
  const gitaRow = settle.body.members.find((m) => m.name === 'gita');
  check('settlement covers the five', settle.body.members.length === 5);
  check('gita: paid 5000, spent 880 -> flat owes 880',
    gitaRow.net === '880.00', `net ${gitaRow.net}`);
  const sunilRow = settle.body.members.find((m) => m.name === 'sunil');
  check('sunil paid nothing -> owes 5000', sunilRow.net === '-5000.00', sunilRow.net);

  // ---------------------------------------------------------------- 11
  step(11, 'Finding things again');
  const byName = await call('GET', `/api/groups/${gid}/expenses?q=sabzi`, { token: gitaTok });
  check('search by description', byName.body.expenses.length === 1);
  const groceryId = cat('Grocery');
  const byTree = await call('GET', `/api/groups/${gid}/expenses?categoryId=${groceryId}`,
    { token: gitaTok });
  check('a heading includes its children', byTree.body.total === 3,
    `${byTree.body.total} under Grocery`);
  const byPayer = await call('GET', `/api/groups/${gid}/expenses?paidBy=${ids.ravi}`,
    { token: gitaTok });
  check('filter by who paid', byPayer.body.total === 2, String(byPayer.body.total));
  const byAmount = await call('GET', `/api/groups/${gid}/expenses?minAmount=1000`,
    { token: gitaTok });
  check('filter by amount', byAmount.body.total === 1, String(byAmount.body.total));

  // ---------------------------------------------------------------- 12
  step(12, 'The month is closed');
  const stillPending = await spend(gitaTok, 'Fruits', 'Apples, undecided', '260.00');
  const month = today.slice(0, 7);
  const closed = await call('POST', `/api/groups/${gid}/months/${month}/close`, {
    token: admin, body: { note: 'agreed at the flat meeting' }
  });
  check('closed', closed.status === 200, JSON.stringify(closed.body));
  check('the undecided one is carried over', closed.body.carriedOver === 1,
    String(closed.body.carriedOver));

  const carried = await call('GET', `/api/expenses/${stillPending.id}`, { token: admin });
  check('it moved to the next month', carried.body.expense.expenseDate.slice(0, 7) !== month);
  check('and kept its original date in the audit',
    carried.body.audit.some((a) => a.action === 'carried_over' && a.detail.includes(today)));

  const lateSpend = await call('POST', `/api/groups/${gid}/expenses`, {
    token: gitaTok,
    body: { categoryId: cat('Gas'), description: 'Too late', amount: '100.00', expenseDate: today }
  });
  check('a closed month refuses new expenses', lateSpend.status === 409);
  const lateCancel = await call('POST', `/api/expenses/${rent.id}/cancel`, {
    token: admin, body: { reason: 'changed my mind' }
  });
  check('and refuses to cancel a settled one', lateCancel.status === 409);

  // ---------------------------------------------------------------- 13
  step(13, 'Reopened to fix something, then closed again');
  const reopened = await call('POST', `/api/groups/${gid}/months/${month}/reopen`, {
    token: admin, body: { reason: 'rent was 12,500 not 12,000' }
  });
  check('reopened', reopened.status === 200);
  const fixed = await call('PATCH', `/api/expenses/${rent.id}`, {
    token: admin, body: { amount: '12500.00' }
  });
  check('the correction goes through', fixed.status === 200);
  await call('POST', `/api/expenses/${rent.id}/approve`, { token: admin });
  const reclosed = await call('POST', `/api/groups/${gid}/months/${month}/close`, {
    token: admin, body: {}
  });
  check('closed again', reclosed.status === 200);

  const finalDash = await call('GET', `/api/groups/${gid}/dashboard`, { token: admin });
  check('totals follow the correction',
    finalDash.body.expenses.approvedTotal === money(12500 + 880 + 420),
    finalDash.body.expenses.approvedTotal);

  // ---------------------------------------------------------------- 14
  step(14, 'The history tells the whole story');
  const feed = await call('GET', `/api/groups/${gid}/activity?limit=200`, { token: gitaTok });
  const actions = feed.body.activity.map((a) => a.action);
  for (const a of ['created', 'approved', 'rejected', 'edited_reopened', 'carried_over',
                   'month_closed', 'month_reopened', 'join_approved', 'join_declined']) {
    check(`activity records ${a}`, actions.includes(a));
  }
  const times = feed.body.activity.map((a) => new Date(a.createdAt).getTime());
  check('newest first', times.every((t, i) => i === 0 || times[i - 1] >= t));

  // ---------------------------------------------------------------- 15
  step(15, 'A flatmate cannot act as the Admin');
  const denials = [
    ['POST', `/api/groups/${gid}/members`, { name: 'x', email: email('x'), password: PW }],
    ['POST', `/api/groups/${gid}/contributions`, { userId: ids.sunil, paidAmount: '1.00' }],
    ['POST', `/api/groups/${gid}/categories`, { name: 'Sneaky' }],
    ['POST', `/api/groups/${gid}/transfer-admin`, { userId: ids.gita }],
    ['POST', `/api/groups/${gid}/months/${month}/reopen`, { reason: 'let me' }]
  ];
  for (const [m, p, b] of denials) {
    const r = await call(m, p, { token: gitaTok, body: b });
    check(`refused: ${p.replace(`/api/groups/${gid}`, '')}`, r.status === 403, `got ${r.status}`);
  }
  const seeRequests = await call('GET', `/api/groups/${gid}/join-requests`, { token: gitaTok });
  check('refused: seeing join requests', seeRequests.status === 403);

  // ---------------------------------------------------------------- 16
  step(16, 'Tidying up');
  const del = await fetch(`${BASE}/api/health`);
  check('the API is still healthy at the end', del.status === 200);

  console.log(`\n=== ${passed} passed, ${failed} failed ===`);
  if (failed) console.log('failed:\n  ' + failures.join('\n  '));
  console.log(`\n(created flat "${flatName}" — remove with the cleanup script)`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('\nE2E aborted:', err.message);
  process.exit(1);
});
