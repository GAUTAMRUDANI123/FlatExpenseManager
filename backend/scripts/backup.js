'use strict';

/**
 * Dumps the database to backend/backups/ and prunes old files.
 *
 * This exists because the deployment SETUP.md describes keeps the only copy of
 * every expense on one laptop. A dead disk would take the whole ledger with
 * it, and unlike the code there is nothing in GitHub to restore from.
 *
 *   npm run backup
 *
 * Restore with:
 *   mysql -u root -p flat_expense_manager < backend/backups/<file>.sql
 */

require('dotenv').config();

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const BACKUP_DIR = path.join(__dirname, '..', 'backups');
const KEEP = Number(process.env.BACKUP_KEEP || 14);

/**
 * mysqldump is not always on PATH on Windows, and the portable MySQL builds
 * people use for this project never are. MYSQLDUMP_PATH covers that case.
 */
function resolveMysqldump() {
  const configured = process.env.MYSQLDUMP_PATH;
  if (configured) {
    if (!fs.existsSync(configured)) {
      throw new Error(`MYSQLDUMP_PATH is set to ${configured}, which does not exist`);
    }
    return configured;
  }
  return process.platform === 'win32' ? 'mysqldump.exe' : 'mysqldump';
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  );
}

/** Keeps the newest KEEP dumps and deletes the rest. */
function prune() {
  const files = fs
    .readdirSync(BACKUP_DIR)
    .filter((f) => f.endsWith('.sql'))
    .map((f) => ({ name: f, time: fs.statSync(path.join(BACKUP_DIR, f)).mtimeMs }))
    .sort((a, b) => b.time - a.time);

  for (const stale of files.slice(KEEP)) {
    fs.unlinkSync(path.join(BACKUP_DIR, stale.name));
    console.log(`  pruned ${stale.name}`);
  }
}

async function main() {
  fs.mkdirSync(BACKUP_DIR, { recursive: true });

  const database = process.env.DB_NAME || 'flat_expense_manager';
  const outPath = path.join(BACKUP_DIR, `${database}_${timestamp()}.sql`);
  const bin = resolveMysqldump();

  const args = [
    `--host=${process.env.DB_HOST || '127.0.0.1'}`,
    `--port=${process.env.DB_PORT || 3306}`,
    `--user=${process.env.DB_USER || 'root'}`,
    // Without this a dump taken mid-write can capture a half-finished
    // transaction: an approved expense whose audit row never made it.
    '--single-transaction',
    '--routines',
    '--triggers',
    // Dumping tablespaces needs the PROCESS privilege, which the application
    // user deliberately does not have. The data dump does not need it, so
    // skipping it avoids a warning on every single run.
    '--no-tablespaces',
    // A hosted MySQL runs with GTIDs on, and a dump taken from one carries
    // their IDs. Restoring that into a different server is refused unless the
    // target is empty of them — which is exactly the moment a backup has to
    // work, so the IDs are left out and the dump stays restorable anywhere.
    '--set-gtid-purged=OFF',
    database
  ];

  // The password goes through the environment, not argv, so it does not show
  // up in the process list for every other user on the machine.
  const env = { ...process.env };
  if (process.env.DB_PASSWORD) env.MYSQL_PWD = process.env.DB_PASSWORD;

  console.log(`Dumping ${database} ...`);

  const out = fs.createWriteStream(outPath);
  const child = spawn(bin, args, { env });

  child.stdout.pipe(out);

  let stderr = '';
  child.stderr.on('data', (chunk) => {
    stderr += chunk.toString();
  });

  const code = await new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', resolve);
  });

  out.close();

  // mysqldump warns about the password on every run even when it succeeds, so
  // only genuine failures are worth surfacing.
  const realErrors = stderr
    .split('\n')
    .filter((line) => line.trim() && !line.includes('Using a password'))
    .join('\n');

  if (code !== 0) {
    fs.unlinkSync(outPath);
    throw new Error(`mysqldump exited with ${code}\n${realErrors}`);
  }

  const bytes = fs.statSync(outPath).size;
  if (bytes === 0) {
    fs.unlinkSync(outPath);
    throw new Error('mysqldump produced an empty file');
  }

  console.log(`  ${path.basename(outPath)}  (${(bytes / 1024).toFixed(1)} KB)`);
  if (realErrors) console.log(`  note: ${realErrors.trim()}`);

  prune();
  console.log(`\nKeeping the newest ${KEEP} dumps in backend/backups/.`);

  copyOffMachine(outPath);
}

/**
 * Copies the dump somewhere that is not this disk.
 *
 * A backup beside the database it came from protects against a bad query and
 * nothing else. The failure people actually lose their records to is the drive
 * dying, and that takes the database and every local dump with it in one go.
 *
 * BACKUP_COPY_TO is usually a synced folder — OneDrive, Google Drive, Dropbox —
 * so the file leaves the machine without any upload code here. A second disk or
 * a USB stick works just as well.
 */
function copyOffMachine(outPath) {
  const target = process.env.BACKUP_COPY_TO;
  if (!target) {
    console.log(
      '\nNo BACKUP_COPY_TO set, so this dump exists only on this disk.' +
        '\nA drive failure would take the database and this backup together.' +
        '\nSet it in .env to a synced folder, for example:' +
        '\n  BACKUP_COPY_TO=C:/Users/you/OneDrive/FlatExpenseBackups'
    );
    return;
  }

  try {
    fs.mkdirSync(target, { recursive: true });
    const copied = path.join(target, path.basename(outPath));
    fs.copyFileSync(outPath, copied);

    // Prune the off-machine copies too, or the synced folder grows forever.
    const stale = fs
      .readdirSync(target)
      .filter((f) => f.endsWith('.sql'))
      .map((f) => ({ name: f, time: fs.statSync(path.join(target, f)).mtimeMs }))
      .sort((a, b) => b.time - a.time)
      .slice(KEEP);
    for (const f of stale) fs.unlinkSync(path.join(target, f.name));

    console.log(`\nCopied off this machine to:\n  ${copied}`);
  } catch (err) {
    // A failed copy must not look like a successful backup.
    console.error(
      `\nWARNING: the dump was written but could NOT be copied to ${target}` +
        `\n  ${err.message}` +
        '\nIt currently exists only on this disk.'
    );
    process.exitCode = 1;
  }
}

main().catch((err) => {
  if (err.code === 'ENOENT') {
    console.error(
      '\nCould not run mysqldump. It is not on PATH.' +
        '\nSet MYSQLDUMP_PATH in .env to its full path, for example:' +
        '\n  MYSQLDUMP_PATH=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe'
    );
  } else {
    console.error(`\n${err.message}`);
  }
  process.exit(1);
});
