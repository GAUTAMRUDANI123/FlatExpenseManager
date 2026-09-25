'use strict';

const { ApiError } = require('./errors');

function requireString(body, field, { max = 255, min = 1 } = {}) {
  const value = body[field];
  if (typeof value !== 'string' || value.trim().length < min) {
    throw new ApiError(400, `${field} is required`);
  }
  const trimmed = value.trim();
  if (trimmed.length > max) {
    throw new ApiError(400, `${field} must be at most ${max} characters`);
  }
  return trimmed;
}

function optionalString(body, field, { max = 255 } = {}) {
  const value = body[field];
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string') throw new ApiError(400, `${field} must be text`);
  const trimmed = value.trim();
  if (trimmed.length > max) {
    throw new ApiError(400, `${field} must be at most ${max} characters`);
  }
  return trimmed;
}

/**
 * Section 15: "Amount must be greater than zero."
 * Returns a fixed-2dp string so it goes into DECIMAL(12,2) untouched by float.
 */
function requireAmount(body, field = 'amount') {
  const raw = body[field];
  const value = typeof raw === 'string' ? Number(raw.trim()) : raw;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new ApiError(400, `${field} must be a number`);
  }
  if (value <= 0) {
    throw new ApiError(400, `${field} must be greater than zero`);
  }
  if (value > 99999999.99) {
    throw new ApiError(400, `${field} is too large`);
  }
  return value.toFixed(2);
}

function requireId(body, field) {
  const value = Number(body[field]);
  if (!Number.isInteger(value) || value <= 0) {
    throw new ApiError(400, `${field} is required`);
  }
  return value;
}

function optionalId(body, field) {
  if (body[field] === undefined || body[field] === null || body[field] === '') return null;
  return requireId(body, field);
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** Accepts YYYY-MM-DD; defaults to today when absent. */
function optionalDate(body, field, fallback) {
  const value = body[field];
  if (value === undefined || value === null || value === '') return fallback;
  if (typeof value !== 'string' || !DATE_RE.test(value)) {
    throw new ApiError(400, `${field} must be in YYYY-MM-DD format`);
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    throw new ApiError(400, `${field} is not a real date`);
  }
  return value;
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

/** Normalises "2026-09" or "2026-09-14" to the month's first day. */
function normaliseMonth(value, fallback) {
  if (value === undefined || value === null || value === '') return fallback;
  if (typeof value !== 'string') throw new ApiError(400, 'month must be text');
  const trimmed = value.trim();
  if (/^\d{4}-\d{2}$/.test(trimmed)) return `${trimmed}-01`;
  if (DATE_RE.test(trimmed)) return `${trimmed.slice(0, 7)}-01`;
  throw new ApiError(400, 'month must be YYYY-MM');
}

function currentMonth() {
  return `${new Date().toISOString().slice(0, 7)}-01`;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function requireEmail(body, field = 'email') {
  const value = requireString(body, field, { max: 190 }).toLowerCase();
  if (!EMAIL_RE.test(value)) throw new ApiError(400, 'email is not valid');
  return value;
}

function requirePassword(body, field = 'password') {
  const value = body[field];
  if (typeof value !== 'string' || value.length < 8) {
    throw new ApiError(400, 'password must be at least 8 characters');
  }
  if (value.length > 200) throw new ApiError(400, 'password is too long');
  return value;
}

module.exports = {
  requireString,
  optionalString,
  requireAmount,
  requireId,
  optionalId,
  optionalDate,
  todayIso,
  normaliseMonth,
  currentMonth,
  requireEmail,
  requirePassword
};
