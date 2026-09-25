'use strict';

require('dotenv').config();

const express = require('express');
const cors = require('cors');

const { pool } = require('./db/pool');
const { notFound, errorHandler } = require('./middleware/errors');
const { router: authRouter } = require('./routes/auth');
const { router: groupsRouter } = require('./routes/groups');
const { router: expensesRouter } = require('./routes/expenses');

const app = express();

app.use(cors());
app.use(express.json({ limit: '1mb' }));

app.get('/api/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ ok: true, db: 'up' });
  } catch (err) {
    res.status(503).json({ ok: false, db: 'down', message: err.message });
  }
});

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
