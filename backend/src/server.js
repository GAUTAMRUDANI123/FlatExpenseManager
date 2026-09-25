'use strict';

require('dotenv').config();

const express = require('express');
const cors = require('cors');

const { pool } = require('./db/pool');
const { notFound, errorHandler } = require('./middleware/errors');
const { authLimiter, apiLimiter } = require('./middleware/rateLimit');
const { router: authRouter } = require('./routes/auth');
const { router: groupsRouter } = require('./routes/groups');
const { router: expensesRouter } = require('./routes/expenses');

const app = express();

/**
 * Cloudflare Tunnel runs beside this process and connects over loopback, so
 * every request arrives from 127.0.0.1 with the real client address in
 * X-Forwarded-For. Trusting only loopback means that header is believed when
 * the tunnel sets it, and ignored if anything else tries to — which matters,
 * because the rate limiter buckets by client IP and a spoofable header would
 * let an attacker mint a fresh allowance per request.
 */
app.set('trust proxy', process.env.TRUST_PROXY || 'loopback');

/**
 * The Android client is not a browser and is unaffected by CORS. Leaving the
 * origin open was fine while this was localhost-only; published, it lets any
 * website script the API using a logged-in user's token. Set CORS_ORIGIN to a
 * comma-separated list if a web frontend is ever added; unset means no browser
 * origin is allowed, which is the right default for a mobile-only API.
 */
const corsOrigins = (process.env.CORS_ORIGIN || '')
  .split(',')
  .map((o) => o.trim())
  .filter(Boolean);

app.use(cors({ origin: corsOrigins.length > 0 ? corsOrigins : false }));
app.use(express.json({ limit: '1mb' }));
app.use('/api', apiLimiter);

app.get('/api/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ ok: true, db: 'up' });
  } catch (err) {
    res.status(503).json({ ok: false, db: 'down', message: err.message });
  }
});

// Sign-in and registration carry the brute-force risk, so they sit behind the
// stricter budget as well as the general one.
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);

app.use('/api/auth', authRouter);
app.use('/api/groups', groupsRouter);
app.use('/api/expenses', expensesRouter);

app.use(notFound);
app.use(errorHandler);

const port = Number(process.env.PORT || 4000);

if (require.main === module) {
  app.listen(port, () => {
    console.log(`Flat Expense Manager API listening on http://localhost:${port}`);
  });
}

module.exports = app;
