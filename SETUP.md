# Running this for your flat

How to get five flatmates using the app against one shared database, without
paying for hosting.

## The shape of it

```
   your laptop                          the internet            everyone's phones
 ┌──────────────┐                                            ┌──────────────┐
 │  MySQL       │                                            │  Flat        │
 │    ▲         │   ┌────────────┐      https://...          │  Expenses    │
 │    │         │◄──┤ cloudflared ├───────────────────────────┤  (5 phones)  │
 │  Node API    │   └────────────┘                            └──────────────┘
 │  :4000       │
 └──────────────┘
```

One laptop holds the database and runs the API. `cloudflared` gives it an
HTTPS address reachable from anywhere. Everyone's phone talks to that address.

**Why not just use the WiFi?** You can, but then the app only works inside the
flat — no checking the balance from the office, and no HTTPS. The tunnel costs
nothing extra and removes both limits.

**What it still depends on:** the laptop. While it is asleep or off, nobody can
add an expense. If that is a problem, this is the point where real hosting
starts to earn its keep.

---

## 1. Database

Install MySQL 8, then create the schema and an application account:

```bash
cd backend
cp .env.example .env
```

Fill in `DB_PASSWORD` and generate a real `JWT_SECRET`:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Create the tables, as a user with `CREATE` rights:

```bash
DB_USER=root DB_PASSWORD=your-root-password npm run migrate
```

Then make the account the app will actually use — not `root`:

```sql
CREATE USER 'flatapp'@'127.0.0.1' IDENTIFIED BY 'your-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON flat_expense_manager.* TO 'flatapp'@'127.0.0.1';
```

## 2. API

```bash
npm install
npm start
curl http://localhost:4000/api/health     # {"ok":true,"db":"up"}
```

**Do not run `npm run seed`** for a real flat. It creates six demo accounts
that all share the password `password123`, and this repository is public. Seed
data is for trying the app out, not for running it. Create your own account
instead:

Open the app, tap **Create a new flat instead**, and enter your name, email,
password and your flat's name. That makes you the **Admin** — the account that
holds the common pot, approves spending and is never billed a contribution.
The five flatmates get added in step 6, so the flat ends up with six accounts
in total.

## 3. The tunnel

Install `cloudflared`:

```powershell
winget install --id Cloudflare.cloudflared
```

Now pick one of two options.

### Option A — quick tunnel (no account, no domain)

```bash
cloudflared tunnel --url http://localhost:4000
```

It prints an address like `https://random-words-here.trycloudflare.com`. That
is your API address.

**The catch:** the address changes every time you restart `cloudflared`, and
everyone has to re-enter the new one in the app. Fine for testing; irritating
as a permanent arrangement.

### Option B — named tunnel (stable address)

Needs a free Cloudflare account and a domain you control.

```bash
cloudflared tunnel login
cloudflared tunnel create flat-expenses
cloudflared tunnel route dns flat-expenses flat.yourdomain.com
cloudflared tunnel run --url http://localhost:4000 flat-expenses
```

The address is now `https://flat.yourdomain.com` and stays that way across
restarts. Set it up once in everyone's app and forget about it. This is the one
to use if the flat is actually going to rely on the app.

To have it start with the laptop:

```powershell
cloudflared service install
```

## 4. Build the app

```bash
cd android
./gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

> **Note:** release builds are currently signed with the debug key. That is
> fine for sharing the file among yourselves — it installs and runs. It is not
> suitable for the Play Store, and Android will refuse to *update* an app if
> the signing key ever changes, so generate a real keystore before you start
> handing out versions you intend to upgrade later.

Send the APK round however you like — WhatsApp, Drive, a USB cable. Everyone
needs to allow "install from unknown sources" once.

## 5. Point each phone at the API

On each phone, after installing:

1. Open the app
2. On the login screen tap **Server settings** (or **More → Profile**)
3. Enter the tunnel address, including the trailing slash:
   `https://flat.yourdomain.com/`
4. Tap **Save**

## 6. Create the five flatmate accounts

As Admin: **More → Members → Add flatmate**. Enter each person's name, email
and a temporary password.

Tell them to change it on first sign-in: **More → Profile → Change password**.
Nothing forces this, so it is worth actually chasing.

The cap is five *flatmates*; you as Admin sit outside it, so a full flat is six
accounts. If someone moves out, deactivate them (**Members → ⋮ → Deactivate**)
to free the slot — their past expenses stay in the history, which is why there
is no delete.

---

## Keeping it running

**Stop the laptop sleeping.** Settings → System → Power → Screen and sleep →
set sleep to Never while plugged in. An asleep laptop is an app that is down
for everyone.

**Start the API automatically.** The simplest reliable way on Windows is Task
Scheduler: create a task that runs `npm start` in `backend/`, triggered "At log
on", with "Run whether user is logged on or not" ticked.

## Backups

This is the part people skip and then regret. Every expense your flat records
lives on one disk. The code is safe in GitHub; the data is not anywhere.

```bash
cd backend
npm run backup
```

Writes a timestamped dump to `backend/backups/` and keeps the newest 14. Those
files hold real emails and password hashes, so they are gitignored — do not
force them in.

If `mysqldump` is not on your PATH, set `MYSQLDUMP_PATH` in `.env`:

```
MYSQLDUMP_PATH=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe
```

### Getting the backup off the laptop

A dump sitting next to the database protects you against a bad query and
nothing else. The failure that actually loses people their records is the
drive dying, and that takes the database and every local dump with it in one
go.

Set `BACKUP_COPY_TO` in `.env` to a synced folder and every dump is copied
there automatically:

```
BACKUP_COPY_TO=C:/Users/you/OneDrive/FlatExpenseBackups
```

OneDrive, Google Drive or Dropbox all work — the sync client does the
uploading, so there are no cloud credentials in this project. A second drive or
a USB stick works too, as long as it is not the disk the database is on. The
newest 14 are kept in both places; older ones are pruned.

If the copy fails, the script says so loudly and exits non-zero rather than
reporting a successful backup that only exists in one place.

### Running it daily without remembering

`scripts/backup.cmd` is a wrapper for Task Scheduler, which handles a batch
file far more predictably than a node command line:

```powershell
schtasks /Create /TN "FlatExpense Daily Backup" ^
  /TR "C:\path	oackend\scriptsackup.cmd" /SC DAILY /ST 21:00
```

Note that a scheduled task does not inherit your shell, so anything the script
needs must be in `.env` rather than set on the command line — `MYSQLDUMP_PATH`
especially. Run the task once by hand (`schtasks /Run /TN "..."`) and check
`backend/backups/backup.log` before trusting it; a backup you have never seen
succeed is not a backup.

Restore with:

```bash
mysql -u root -p flat_expense_manager < backend/backups/<file>.sql
```

## Putting it in the cloud instead

Everything above keeps the database on your laptop and reaches it through a
tunnel. That works, but it has three standing costs: the address changes when
the tunnel restarts, the laptop has to stay awake, and every expense the flat
has ever recorded sits on one disk.

Hosting the API removes all three at once. The trade is a cold start — a free
tier sleeps when idle, so the first person to open the app after a quiet spell
waits half a minute — and your data sitting on someone else's server.

### The shape of it

```
   six phones  ->  https://your-api.onrender.com  ->  managed MySQL
```

Nothing on anyone's phone, an address that never changes, and no laptop in the
path at all.

### 1. A managed MySQL

The database stays MySQL. Render's own managed database is PostgreSQL, and
this app is MySQL throughout — the schema, the queries, the DECIMAL handling
that keeps the rupee arithmetic exact. Rewriting all of that to change engine
would be a large change with real risk to the money, for no benefit the flat
would ever see.

So use a provider that offers MySQL on a free plan, and keep its connection
details: host, port, user, password, database name, and its CA certificate.

### 2. Load the schema and your data

From this laptop, pointed at the hosted database:

```bash
cd backend
DB_HOST=<host> DB_PORT=<port> DB_USER=<user> DB_PASSWORD=<pw>   DB_NAME=<db> DB_SSL=true npm run migrate
```

Then move what you already have. `npm run backup` writes a dump; load it into
the hosted database with whatever client the provider gives you. It is a small
file — a flat's whole history is tens of kilobytes.

### 3. Deploy the API

`backend/render.yaml` describes the service: free plan, `npm start`, and
`/api/health` as the health check, so a service that is running but cannot
reach its database is correctly treated as unhealthy rather than quietly
serving errors.

Point Render at this repository and fill in the `DB_*` variables it asks for.
`JWT_SECRET` is generated once and then left alone — regenerating it signs
everybody out.

### 4. Point the app at it

On each phone: **Server settings**, then the Render address with a trailing
slash. Once. It never changes again.

### What to watch

**The first request after idle is slow.** Free tiers sleep. Half a minute of
apparent hanging, then normal speed. Tell your flatmates, or they will report
it as broken.

**Back up anyway.** The database is someone else's now, but a free tier is
still a free tier — `npm run backup` works against a hosted database exactly
the same way, and `BACKUP_COPY_TO` still puts the dump somewhere you control.

**`DB_CA` is worth setting.** Without it the connection to the database is
encrypted but the server is unverified. The provider gives you the certificate;
paste it in.

## Close signup once your flat exists

Registration is open so the very first flat can be created. After that the
Admin adds each flatmate from the Members screen, so nothing needs the signup
form any more — and leaving it open on a published API means anyone who finds
the address can create accounts and groups in your database.

They could not read your flat's data. Every group-scoped route checks
membership, and a stranger who registers gets their own empty flat and a 403
on everything of yours. But a signup form nobody needs is still a signup form
on the public internet.

Once your five flatmates have accounts, set this in `.env` and restart:

```
ALLOW_REGISTRATION=false
```

Existing sign-ins keep working, and the Admin can still add flatmates. Only
the "Create a new flat" path is refused, with a message telling the person to
ask their Admin.

## Before you trust it with real money

- [ ] `JWT_SECRET` is the random 96-character string, not `change-me`
- [ ] `ALLOW_REGISTRATION=false`, now that the flat exists
- [ ] `BACKUP_COPY_TO` points somewhere off this laptop
- [ ] Seed accounts deleted, or every password changed
- [ ] The app connects to `https://`, never `http://`
- [ ] Everyone has changed their temporary password
- [ ] `npm run backup` has been run at least once, and the file opens
- [ ] A backup copy exists somewhere other than the laptop

## What is protected, and what is not

The API is on the public internet once the tunnel is up. What guards it:

- Passwords are bcrypt hashed; the plaintext is never stored or logged
- Every endpoint except login and register requires a valid JWT
- You can only read your own flat's data, and someone else's expense returns
  **404 rather than 403**, so IDs cannot be probed
- Approve, reject, and the admin actions are enforced server-side, not merely
  hidden in the UI
- Failed sign-ins are capped at 20 per 15 minutes per address; successful ones
  do not count against it
- No browser origin is allowed, so a web page cannot script the API
- No UPI PIN, bank password or OTP is stored — there is no column for one and
  no endpoint accepts one

What it does not do: there is no 2FA, no password-reset flow (the Admin has to
create a replacement account), no intrusion detection, and no encryption of the
database file at rest. For five flatmates splitting grocery bills that is a
sensible place to stop. It is not a banking app.

## When something is wrong

**"Cannot reach the server"** — is the API up (`curl http://localhost:4000/api/health`
on the laptop), is `cloudflared` still running, and does the address in Profile
end with `/`?

**Worked yesterday, not today** — if you used a quick tunnel (Option A), the
address changed when it restarted. This is the reason to move to Option B.

**"Too many failed sign-in attempts"** — the brute-force cap. Everyone in the
flat shares one public address, so several people fumbling passwords at once
can trip it together. It clears after 15 minutes.

**Cleartext blocked** — the app refuses plain HTTP to anything except
`10.0.2.2`, `localhost` and `127.0.0.1`. Use the HTTPS tunnel address. If you
genuinely need to hit a laptop over the LAN, add that exact IP to
[network_security_config.xml](android/app/src/main/res/xml/network_security_config.xml) —
and note that Android matches those entries literally, with no subnet support,
so a range like `192.168.0.0` matches nothing.
