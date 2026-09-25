-- Flat Common Expense Manager — schema
-- Mirrors Appendix Table 5, with the audit columns section 16 asks for.
--
-- Money is stored as DECIMAL(12,2), never FLOAT: these are rupee amounts that
-- get summed into monthly totals, and binary floating point would drift.

CREATE DATABASE IF NOT EXISTS flat_expense_manager
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE flat_expense_manager;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(120)  NOT NULL,
  email         VARCHAR(190)  NOT NULL UNIQUE,
  phone         VARCHAR(20)   NULL,
  password_hash VARCHAR(255)  NOT NULL,
  role          ENUM('admin','member') NOT NULL DEFAULT 'member',
  status        ENUM('active','inactive') NOT NULL DEFAULT 'active',
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- groups  ("groups" is reserved in MySQL 8, so the table is back-quoted
--          everywhere it appears)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `groups` (
  id         BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  name       VARCHAR(120) NOT NULL,
  admin_id   BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_groups_admin FOREIGN KEY (admin_id) REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- group_members
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS group_members (
  id        BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  group_id  BIGINT UNSIGNED NOT NULL,
  user_id   BIGINT UNSIGNED NOT NULL,
  joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status    ENUM('active','inactive') NOT NULL DEFAULT 'active',
  UNIQUE KEY uq_group_user (group_id, user_id),
  CONSTRAINT fk_gm_group FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  CONSTRAINT fk_gm_user  FOREIGN KEY (user_id)  REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- categories — scoped to a group so one flat's edits cannot touch another's
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS categories (
  id         BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  group_id   BIGINT UNSIGNED NOT NULL,
  name       VARCHAR(80) NOT NULL,
  icon       VARCHAR(40) NULL,
  is_active  TINYINT(1)  NOT NULL DEFAULT 1,
  sort_order INT         NOT NULL DEFAULT 0,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_category_name (group_id, name),
  CONSTRAINT fk_cat_group FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- monthly_contributions — one row per member per month.
-- `month` is the first day of that month (2026-09-01) so it sorts and ranges.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS monthly_contributions (
  id              BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  group_id        BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  month           DATE NOT NULL,
  expected_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  paid_amount     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  status          ENUM('pending','partial','paid') NOT NULL DEFAULT 'pending',
  paid_at         TIMESTAMP NULL,
  note            VARCHAR(255) NULL,
  recorded_by     BIGINT UNSIGNED NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_contribution (group_id, user_id, month),
  CONSTRAINT fk_mc_group FOREIGN KEY (group_id) REFERENCES `groups`(id) ON DELETE CASCADE,
  CONSTRAINT fk_mc_user  FOREIGN KEY (user_id)  REFERENCES users(id),
  CONSTRAINT fk_mc_rec   FOREIGN KEY (recorded_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- expenses
--
-- paid_by  = who actually handed over the money
-- split_to = the member/account the expense sits against; defaults to the
--            group Admin. This is NOT an equal split across members — the full
--            amount is stored on one row against one split_to.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expenses (
  id               BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  group_id         BIGINT UNSIGNED NOT NULL,
  category_id      BIGINT UNSIGNED NOT NULL,
  description      VARCHAR(255)  NOT NULL,
  amount           DECIMAL(12,2) NOT NULL,
  paid_by          BIGINT UNSIGNED NOT NULL,
  split_to         BIGINT UNSIGNED NOT NULL,
  expense_date     DATE NOT NULL,
  status           ENUM('pending','approved','rejected','cancelled') NOT NULL DEFAULT 'pending',
  created_by       BIGINT UNSIGNED NOT NULL,
  approved_by      BIGINT UNSIGNED NULL,
  approved_at      TIMESTAMP NULL,
  rejection_reason VARCHAR(255) NULL,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_amount_positive CHECK (amount > 0),
  CONSTRAINT fk_exp_group FOREIGN KEY (group_id)    REFERENCES `groups`(id) ON DELETE CASCADE,
  CONSTRAINT fk_exp_cat   FOREIGN KEY (category_id) REFERENCES categories(id),
  CONSTRAINT fk_exp_paid  FOREIGN KEY (paid_by)     REFERENCES users(id),
  CONSTRAINT fk_exp_split FOREIGN KEY (split_to)    REFERENCES users(id),
  CONSTRAINT fk_exp_cby   FOREIGN KEY (created_by)  REFERENCES users(id),
  CONSTRAINT fk_exp_aby   FOREIGN KEY (approved_by) REFERENCES users(id),
  INDEX idx_exp_group_date (group_id, expense_date),
  INDEX idx_exp_group_status (group_id, status)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- expense_receipts — table exists now; upload is a section 18 future feature
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expense_receipts (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  expense_id  BIGINT UNSIGNED NOT NULL,
  file_url    VARCHAR(500) NOT NULL,
  file_name   VARCHAR(255) NOT NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_rec_exp  FOREIGN KEY (expense_id)  REFERENCES expenses(id) ON DELETE CASCADE,
  CONSTRAINT fk_rec_user FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- expense_audit — section 15 forbids silently editing an approved expense.
-- Every status change and every edit lands here.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expense_audit (
  id          BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  expense_id  BIGINT UNSIGNED NOT NULL,
  action      VARCHAR(40) NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status   VARCHAR(20) NULL,
  detail      TEXT NULL,
  actor_id    BIGINT UNSIGNED NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_aud_exp  FOREIGN KEY (expense_id) REFERENCES expenses(id) ON DELETE CASCADE,
  CONSTRAINT fk_aud_user FOREIGN KEY (actor_id)   REFERENCES users(id),
  INDEX idx_aud_expense (expense_id, created_at)
) ENGINE=InnoDB;
