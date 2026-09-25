'use strict';

/**
 * Section 16 asks for the login endpoint to be protected. On a LAN-only
 * install that was theoretical; once the API is published through a tunnel it
 * is reachable from anywhere, and five accounts with human-chosen passwords
 * are exactly what credential stuffing looks for.
 *
 * Sizing note: through the tunnel, everyone in one flat shares a single public
 * IP, so a per-IP budget is really a per-household budget. The login limiter
 * therefore counts only *failed* attempts — signing in correctly never uses up
 * anyone's allowance — and the ceiling is high enough that five people setting
 * up at once will not lock each other out, while still cutting a brute force
 * from millions of guesses an hour to eighty.
 */

const { rateLimit } = require('express-rate-limit');

const WINDOW_MS = 15 * 60 * 1000;

function limitMessage(retryAfterMinutes) {
  return {
    error: {
      message:
        'Too many failed sign-in attempts. Wait about ' +
        `${retryAfterMinutes} minutes and try again.`
    }
  };
}

/** Failed sign-ins and registrations. Successful ones are not counted. */
const authLimiter = rateLimit({
  windowMs: WINDOW_MS,
  limit: Number(process.env.RATE_LIMIT_AUTH || 20),
  skipSuccessfulRequests: true,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: limitMessage(15),
  // The app reads error.message, so the limiter has to speak the same shape
  // the rest of the API uses rather than express-rate-limit's plain text.
  handler: (_req, res, _next, options) => {
    res.status(options.statusCode).json(options.message);
  }
});

/**
 * A backstop for everything else. Deliberately loose: one dashboard refresh
 * fans out to several endpoints, and a whole flat shares one address.
 */
const apiLimiter = rateLimit({
  windowMs: WINDOW_MS,
  limit: Number(process.env.RATE_LIMIT_API || 1000),
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: { message: 'Too many requests. Try again shortly.' } },
  handler: (_req, res, _next, options) => {
    res.status(options.statusCode).json(options.message);
  }
});

module.exports = { authLimiter, apiLimiter };
