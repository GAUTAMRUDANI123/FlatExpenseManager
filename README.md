# Flat Common Expense Manager

Implementation of the *Flat Common Expense Manager* requirements document
(v1.0): five flatmates, one Admin who holds the common account, monthly
contributions, and flat expenses that the Admin approves or rejects.

The rule the spec repeats most, implemented in both tiers: **Split To is not an
equal split.** A ₹1,000 grocery expense is stored once, in full, with
`paid_by = Gautam` and `split_to = Rahul (Admin)`. No ₹200-per-person share is
ever created.

## What's here

```
backend/     Node.js + Express + MySQL API (the spec's section 17 stack)
android/     Kotlin + Jetpack Compose client
```

The spec recommends a React frontend; this build uses a native Android client
against the same API instead. The API is unchanged by that choice — a React app
can be added later against the same endpoints.

## Requirements implemented

| Spec section | Where |
|---|---|
| 2, 3 — roles, one Admin per group, five-member cap | [auth.js](backend/src/middleware/auth.js), [groups.js](backend/src/routes/groups.js) |
| 4 — monthly contributions | `/contributions` routes, [ContributionsScreen](android/app/src/main/java/com/flatexpense/ui/screens/AdminScreens.kt) |
| 5, 6 — add expense, Split To defaults to Admin | [AddExpenseScreen.kt](android/app/src/main/java/com/flatexpense/ui/screens/AddExpenseScreen.kt) + server-side default |
| 7 — approval workflow | [expenses.js](backend/src/routes/expenses.js) |
| 8 — categories | [categories.js](backend/src/services/categories.js), Categories screen |
| 9 — dashboard | `/dashboard`, [DashboardScreen.kt](android/app/src/main/java/com/flatexpense/ui/screens/DashboardScreen.kt) |
| 10 — monthly reports | `/reports/monthly`, Reports screen |
| 11 — Pending / Approved / Rejected / Cancelled | `expenses.status` enum |
| 12 — main screens | 15 screens in [ui/screens/](android/app/src/main/java/com/flatexpense/ui/screens/) |
| 13 — database design | [schema.sql](backend/sql/schema.sql) |
| 14 — API endpoints | all of Table 6, plus admin transfer and bulk contribution |
| 15 — validation rules | [validate.js](backend/src/middleware/validate.js) + per-route checks |
| 16 — security | JWT, bcrypt hashes, group-scoped authorization, audit trail |

Beyond the spec: analytics with charts, a settlement page, a flat-wide activity
log, global search, and month closing.

Not built, because the spec lists them as future work (section 18): receipt
upload (the table exists, no endpoint), recurring expenses, notifications,
Excel/PDF export, multiple groups per user, category budgets.

## Design decisions worth knowing

**Money is never a float.** Amounts are `DECIMAL(12,2)` in MySQL, fixed-2dp
strings over the wire, and are parsed only at the last moment for display. Float
arithmetic on rupee amounts drifts once you start summing monthly totals.

**Admin is a property of the group, not the user.** `groups.admin_id` decides
who can approve, so the check happens in one middleware
(`requireGroupMember` → `req.isGroupAdmin`) rather than trusting a `role`
column that could go stale after an admin transfer.

**Editing an approved expense reopens it.** Section 15 forbids silent edits, so
a `PATCH` to an approved or rejected expense resets it to Pending, clears the
approver, and writes an `edited_reopened` row to `expense_audit`.

**Foreign expenses 404 rather than 403.** A 403 would confirm that someone
else's expense id is real. Membership is checked in the same query that loads
the row.

**A closed month is read-only.** `month_closures` holds the lock, and one guard
(`assertMonthOpen`) sits in front of expense creation, edits, every decision
route and both contribution routes — so last month's agreed totals cannot drift
once everyone has signed off. Closing is reversible: the Admin reopens,
corrects, and closes again, and both halves are written to `group_audit`.
Expenses still undecided when a month closes are carried into the next month
rather than being auto-rejected, and the original date is kept in
`expense_audit` so moving it loses nothing.

**Charts pin their own colours.** The app uses dynamic colour, so on Android 12+
the theme comes from the user's wallpaper. Charts therefore fix both the series
colours and the surface they are drawn on — otherwise contrast and
colour-blind separation would depend on somebody's home screen. The palette is
validated in both light and dark; the figures are in
[Charts.kt](android/app/src/main/java/com/flatexpense/ui/common/Charts.kt).

**Category spending is bars, not a pie.** The question is which categories cost
most, and length is read far more accurately than angle — especially across
twelve categories, where slice colours stop being distinguishable at all.

**The recorded balance is cash on hand.** It is contributions *received* minus
*approved* expenses. Unpaid contributions are deliberately excluded — a balance
that counts money nobody has transferred yet is a forecast, not a balance.

## Running it

To actually put this in front of five flatmates rather than just start it
locally, see **[SETUP.md](SETUP.md)** — one laptop, an HTTPS tunnel, and the
backup arrangement that stops a dead disk taking the whole ledger with it.

### 1. Database

Requires MySQL 8. Create the schema:

```bash
mysql -u root -p < backend/sql/schema.sql
```

Then create an application user (do not point the app at `root`):

```sql
CREATE USER 'flatapp'@'127.0.0.1' IDENTIFIED BY 'your-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON flat_expense_manager.* TO 'flatapp'@'127.0.0.1';
```

`npm run migrate` applies the same file from Node, which is useful when there is
no `mysql` client on the machine. It needs an account with `CREATE` rights, so
run it before you switch `.env` over to the application user:

```bash
cd backend
DB_USER=root DB_PASSWORD=your-root-password npm run migrate
```

It is idempotent — `schema.sql` is written entirely with `CREATE ... IF NOT
EXISTS`, so re-running it will not touch existing rows.

### 2. API

```bash
cd backend
cp .env.example .env     # then fill in DB_PASSWORD and JWT_SECRET
npm install
npm run seed             # optional: loads the spec's section 20 example
npm start                # http://localhost:4000
```

Generate a real `JWT_SECRET`:

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Check it came up: `curl http://localhost:4000/api/health`

Run the rule checks against a seeded database:

```bash
npm run smoke
```

### 3. Android app

Open `android/` in Android Studio and Run, or from the command line:

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app defaults to `http://10.0.2.2:4000/`, which is how the **emulator**
reaches the host machine. On a **physical phone**, open *More → Profile* (or
*Server settings* on the login screen) and enter your computer's LAN address,
e.g. `http://192.168.1.5:4000/`. The phone and the computer must be on the same
network, and the API must be reachable through the computer's firewall.

Cleartext HTTP is permitted only for `10.0.2.2`, `localhost` and private
`192.168.x.x` ranges — see
[network_security_config.xml](android/app/src/main/res/xml/network_security_config.xml).
For anything beyond local use, put the API behind HTTPS and remove those
exemptions.

### Seeded accounts

After `npm run seed`, sign in with any of these (password `password123`):

| Email | Role |
|---|---|
| rahul@flat302.test | Admin |
| gautam@flat302.test | Member |
| priya@flat302.test | Member |
| anjali@flat302.test | Member |
| vikram@flat302.test | Member |

## Before you use this for real money

These are local-development defaults, not production settings:

- **The release build is signed with the debug key.** Replace the
  `signingConfig` in [app/build.gradle.kts](android/app/build.gradle.kts) with
  your own keystore before distributing the APK.
- **The API serves plain HTTP** and CORS is wide open. Put it behind TLS and
  restrict origins before exposing it beyond your own network.
- **Seeded accounts share one weak password.** Delete them, or change every
  password, before real use.
- No rate limiting on the login endpoint.

As the spec requires, no UPI PIN, bank password or OTP is stored anywhere —
there is no column for one, and no endpoint accepts one.
