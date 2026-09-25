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

**Do not run `npm run seed`** for a real flat. It creates five demo accounts
that all share the password `password123`, and this repository is public. Seed
data is for trying the app out, not for running it. Create your own account
instead:

Open the app, tap **Create account**, and enter your name, email, password and
your flat's name. That makes you the Admin. The other four get added in step 6.

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

## 6. Create the other four accounts

As Admin: **More → Members → Add member**. Enter each flatmate's name, email
and a temporary password.

Tell them to change it on first sign-in: **More → Profile → Change password**.
Nothing forces this, so it is worth actually chasing.

The group caps at five active members, which is the rule the spec is built
around. If someone moves out, deactivate them (**Members → ⋮ → Deactivate**) to
free the slot — their past expenses stay in the history.

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

Schedule it weekly in Task Scheduler, and occasionally copy the folder
somewhere that is not that laptop — Drive, a USB stick, anywhere else at all.

Restore with:

```bash
mysql -u root -p flat_expense_manager < backend/backups/<file>.sql
```

## Before you trust it with real money

- [ ] `JWT_SECRET` is the random 96-character string, not `change-me`
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
