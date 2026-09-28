'use strict';

/**
 * Checks a database connection before anything depends on it.
 *
 * Pointing the app at a new database and finding out it does not work is a
 * slow way to learn, because the failure arrives as a 503 from a deployed
 * service with the real reason buried in a log. This connects, reports what it
 * found, and names the likely cause when it cannot.
 *
 *   DB_HOST=... DB_PORT=... DB_USER=... DB_PASSWORD=... DB_NAME=... \
 *     DB_SSL=true npm run db:check
 */

require('dotenv').config();

const mysql = require('mysql2/promise');
const { sslOptions } = require('../src/db/ssl');

const TABLES = [
  'users', 'groups', 'group_members', 'categories', 'monthly_contributions',
  'expenses', 'expense_receipts', 'expense_audit', 'group_audit',
  'month_closures', 'join_requests'
];

async function main() {
  const where = `${process.env.DB_USER}@${process.env.DB_HOST}:${process.env.DB_PORT || 3306}`;
  const ssl = sslOptions();
  console.log(`\nConnecting to ${where} / ${process.env.DB_NAME}`);
  console.log(
    `  TLS: ${
      !ssl ? 'off' : ssl.rejectUnauthorized ? 'on, server verified (DB_CA set)'
        : 'on, server NOT verified (no DB_CA)'
    }`
  );

  const started = Date.now();
  const conn = await mysql.createConnection({
    host: process.env.DB_HOST || '127.0.0.1',
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    ssl,
    connectTimeout: 15000
  });
  console.log(`  connected in ${Date.now() - started}ms`);

  try {
    const [[v]] = await conn.query('SELECT VERSION() AS v');
    console.log(`  server: ${v.v}`);

    // A round trip worth measuring: a hosted database is a network away, and
    // the dashboard fans out to several queries per request.
    const pingStart = Date.now();
    for (let i = 0; i < 5; i += 1) await conn.query('SELECT 1');
    console.log(`  round trip: ~${Math.round((Date.now() - pingStart) / 5)}ms per query`);

    const [rows] = await conn.query(
      `SELECT table_name AS n FROM information_schema.tables WHERE table_schema = ?`,
      [process.env.DB_NAME]
    );
    const present = new Set(rows.map((r) => r.n));
    const missing = TABLES.filter((t) => !present.has(t));

    if (present.size === 0) {
      console.log('\n  The database is empty. Run npm run migrate against it next.');
    } else if (missing.length > 0) {
      console.log(`\n  ${present.size} tables, but missing: ${missing.join(', ')}`);
      console.log('  Run npm run migrate against it to add what is missing.');
    } else {
      console.log(`\n  All ${TABLES.length} tables present.`);
      const [[u]] = await conn.query('SELECT COUNT(*) AS n FROM users');
      const [[g]] = await conn.query('SELECT COUNT(*) AS n FROM `groups`');
      const [[e]] = await conn.query('SELECT COUNT(*) AS n FROM expenses');
      console.log(`  ${g.n} flat(s), ${u.n} account(s), ${e.n} expense(s).`);
    }

    // Writing is a separate permission from reading, and a read-only user
    // looks perfectly healthy until the first expense is added.
    //
    // Deliberately an UPDATE matching no rows, not a temporary table: the
    // application account is granted SELECT, INSERT, UPDATE and DELETE and
    // nothing else, so checking for CREATE would fail on a correctly
    // restricted user and send someone off widening permissions that were
    // right all along.
    if (present.size > 0) {
      await conn.query('UPDATE users SET name = name WHERE id = 0');
      console.log('  write access: yes');
    }

    console.log('\n  Looks usable.\n');
  } finally {
    await conn.end();
  }
}

main().catch((err) => {
  const hint = {
    ENOTFOUND: 'The host name did not resolve. Check DB_HOST for a typo.',
    ECONNREFUSED: 'Nothing accepted the connection. Check DB_PORT, and that the database is running.',
    ETIMEDOUT: 'The connection timed out — often a firewall or an IP allow-list on the provider.',
    ER_ACCESS_DENIED_ERROR: 'The user or password was rejected.',
    ER_BAD_DB_ERROR: 'That database name does not exist on the server yet.',
    HANDSHAKE_NO_SSL_SUPPORT: 'The server does not do TLS. Unset DB_SSL for a local database.'
  }[err.code];

  console.error(`\n  FAILED: ${err.message}`);
  if (hint) console.error(`  ${hint}`);
  if (!hint && /SSL|certificate/i.test(err.message)) {
    console.error('  A TLS problem. Try without DB_CA first to confirm the rest works.');
  }
  console.error('');
  process.exit(1);
});
