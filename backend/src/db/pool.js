'use strict';

const mysql = require('mysql2/promise');
const { sslOptions } = require('./ssl');

const config = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || '',
  database: process.env.DB_NAME || 'flat_expense_manager',
  waitForConnections: true,
  // A hosted free tier hands out far fewer connections than a local server
  // will, and exhausting them takes the whole API down rather than slowing it.
  connectionLimit: Number(process.env.DB_POOL || 10),
  // DECIMAL columns come back as strings by default, which is what we want for
  // money — we convert explicitly at the edges rather than risking float math.
  decimalNumbers: false,
  dateStrings: ['DATE'],
  timezone: 'Z',
  ssl: sslOptions()
};

const pool = mysql.createPool(config);

/** Runs `fn` inside a transaction, rolling back on any throw. */
async function withTransaction(fn) {
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const result = await fn(conn);
    await conn.commit();
    return result;
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
}

module.exports = { pool, withTransaction };
