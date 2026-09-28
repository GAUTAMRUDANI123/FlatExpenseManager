'use strict';

/**
 * Applies sql/schema.sql — creates the database if it is missing, then every
 * table. `npm run migrate` is the documented first step, ahead of `npm run
 * seed`.
 *
 * Safe to re-run: schema.sql is written entirely with CREATE ... IF NOT
 * EXISTS, so this is idempotent and will not touch existing rows.
 *
 * This connects without selecting a database, because schema.sql opens with
 * CREATE DATABASE — the pool in pool.js cannot be used here, as it requires
 * DB_NAME to already exist.
 */

require('dotenv').config();

const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');
const { sslOptions } = require('./ssl');

const SCHEMA_PATH = path.join(__dirname, '..', '..', 'sql', 'schema.sql');

/**
 * Columns added to a table that already exists.
 *
 * schema.sql is written with CREATE TABLE IF NOT EXISTS, which does exactly
 * nothing to a table that is already there — so a column added to the file
 * after someone has run it would never reach their database. Each entry here
 * is checked against information_schema first, making the whole step
 * re-runnable.
 */
const COLUMN_ADDITIONS = [
  {
    table: 'categories',
    column: 'parent_id',
    ddl:
      'ALTER TABLE categories ' +
      'ADD COLUMN parent_id BIGINT UNSIGNED NULL AFTER group_id, ' +
      'ADD CONSTRAINT fk_cat_parent FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE SET NULL, ' +
      'ADD INDEX idx_cat_parent (group_id, parent_id, sort_order)'
  }
];

async function applyColumnAdditions(connection, database) {
  for (const { table, column, ddl } of COLUMN_ADDITIONS) {
    const [rows] = await connection.query(
      `SELECT 1 FROM information_schema.columns
        WHERE table_schema = ? AND table_name = ? AND column_name = ?`,
      [database, table, column]
    );
    if (rows.length === 0) {
      console.log(`  adding ${table}.${column}`);
      await connection.query(`USE \`${database}\`; ${ddl}`);
    }
  }
}

async function main() {
  const schema = fs.readFileSync(SCHEMA_PATH, 'utf8');
  const database = process.env.DB_NAME || 'flat_expense_manager';

  const connection = await mysql.createConnection({
    // A hosted database needs TLS here as much as the pool does; without it
    // the migration fails on a server that the running app connects to fine,
    // which is a confusing way to find out.
    ssl: sslOptions(),
    host: process.env.DB_HOST || '127.0.0.1',
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER || 'root',
    password: process.env.DB_PASSWORD || '',
    // schema.sql issues CREATE DATABASE / USE itself, so no database is
    // selected on connect.
    multipleStatements: true
  });

  try {
    console.log(`Applying ${path.relative(process.cwd(), SCHEMA_PATH)} ...`);
    await connection.query(schema);
    await applyColumnAdditions(connection, database);

    const [tables] = await connection.query(
      `SELECT table_name AS name, table_rows AS approx_rows
         FROM information_schema.tables
        WHERE table_schema = ?
        ORDER BY table_name`,
      [database]
    );

    if (tables.length === 0) {
      throw new Error(`schema.sql ran but ${database} contains no tables`);
    }

    console.log(`\n${database} — ${tables.length} tables:`);
    for (const t of tables) {
      console.log(`  ${t.name}`);
    }
    console.log('\nMigration complete. Next: npm run seed');
  } finally {
    await connection.end();
  }
}

main().catch((err) => {
  // The two mistakes that actually happen: no server on DB_PORT, and an
  // application user that exists but lacks CREATE. Name them rather than
  // printing a bare stack trace.
  if (err.code === 'ECONNREFUSED') {
    console.error(
      `\nCannot reach MySQL at ${process.env.DB_HOST || '127.0.0.1'}:${process.env.DB_PORT || 3306}.` +
        '\nIs the server running, and does DB_PORT in .env match it?'
    );
  } else if (err.code === 'ER_DBACCESS_DENIED_ERROR' || err.code === 'ER_TABLEACCESS_DENIED_ERROR') {
    console.error(
      `\n${process.env.DB_USER} cannot create the schema.` +
        '\nRun this step as a user with CREATE rights, then grant the application' +
        '\nuser SELECT, INSERT, UPDATE, DELETE as the README describes.'
    );
  } else {
    console.error(err);
  }
  process.exit(1);
});
