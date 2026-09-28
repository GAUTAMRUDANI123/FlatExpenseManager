'use strict';

/**
 * TLS settings for the database connection.
 *
 * Its own module rather than living in pool.js, because migrate.js needs it
 * too and must not import the pool: the pool is built around DB_NAME already
 * existing, which during a first migration it does not.
 *
 * A managed database is reached across the public internet and insists on TLS.
 * A MySQL on the same machine usually has none configured and refuses to speak
 * it. DB_SSL picks between them.
 *
 * DB_CA takes the provider's CA certificate, which is what makes the server
 * verified rather than merely encrypted. Without it the traffic is still
 * private but nothing proves the far end is really your database — acceptable
 * to start with, worth fixing, and better stated plainly than hidden.
 */
function sslOptions() {
  if (String(process.env.DB_SSL || '') !== 'true') return undefined;

  const ca = process.env.DB_CA;
  if (ca) {
    // Newlines survive an environment variable as the two characters \n, so
    // they have to be turned back into real ones.
    return { ca: ca.replace(/\\n/g, '\n'), rejectUnauthorized: true };
  }
  return { rejectUnauthorized: false };
}

module.exports = { sslOptions };
