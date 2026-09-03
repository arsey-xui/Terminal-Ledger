-- =============================================================
--  Personal-Accounting-App 云端 MySQL 初始化脚本
--  Database: accounting  |  Charset: utf8mb4
--  运行: mysql -u root -p < sql/init.sql
-- =============================================================

CREATE DATABASE IF NOT EXISTS accounting
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
USE accounting;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS template;
DROP TABLE IF EXISTS budget;
DROP TABLE IF EXISTS transaction_record;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS accounts;

-- ---------------------------------------------------------
--  account：账户
-- ---------------------------------------------------------
CREATE TABLE accounts (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    client_id        VARCHAR(36)   NOT NULL,
    name             VARCHAR(64)   NOT NULL,
    type             VARCHAR(32)   NULL,
    icon             VARCHAR(64)   NULL,
    initial_balance  DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    sort_order       INT           NULL DEFAULT 0,
    last_modified    BIGINT        NOT NULL,
    deleted          TINYINT(1)    NOT NULL DEFAULT 0,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_client_id (client_id),
    KEY idx_account_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------
--  category：分类
-- ---------------------------------------------------------
CREATE TABLE category (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    client_id        VARCHAR(36)  NOT NULL,
    name             VARCHAR(64)  NOT NULL,
    type             VARCHAR(16)  NOT NULL,
    icon             VARCHAR(64)  NULL,
    sort_order       INT          NULL DEFAULT 0,
    last_modified    BIGINT       NOT NULL,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_client_id (client_id),
    KEY idx_category_type (type),
    KEY idx_category_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------
--  transaction_record：记账流水
-- ---------------------------------------------------------
CREATE TABLE transaction_record (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    client_id          VARCHAR(36)   NOT NULL,
    date               DATE          NOT NULL,
    amount             DECIMAL(19,2) NOT NULL,
    type               VARCHAR(16)   NOT NULL,
    category_client_id VARCHAR(36)   NULL,
    account_client_id  VARCHAR(36)   NULL,
    note               VARCHAR(512)  NULL,
    photo_path         VARCHAR(1024) NULL,
    source             VARCHAR(32)   NULL,
    last_modified      BIGINT        NOT NULL,
    deleted            TINYINT(1)    NOT NULL DEFAULT 0,
    created_at         DATETIME      NULL,
    updated_at         DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_client_id (client_id),
    KEY idx_txn_date (date),
    KEY idx_txn_last_modified (last_modified),
    KEY idx_txn_category (category_client_id),
    KEY idx_txn_account (account_client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------
--  budget：月度预算（每个支出分类一个上限）
-- ---------------------------------------------------------
CREATE TABLE budget (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    client_id          VARCHAR(36)   NOT NULL,
    category_client_id VARCHAR(36)   NOT NULL,
    budget_year        INT           NOT NULL,
    budget_month       INT           NOT NULL,
    amount_limit       DECIMAL(19,2) NOT NULL,
    last_modified      BIGINT        NOT NULL,
    deleted            TINYINT(1)    NOT NULL DEFAULT 0,
    created_at         DATETIME      NULL,
    updated_at         DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_budget_cat_month (category_client_id, budget_year, budget_month),
    KEY idx_budget_last_modified (last_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------
--  template：记账模板
-- ---------------------------------------------------------
CREATE TABLE template (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    client_id          VARCHAR(36)   NOT NULL,
    name               VARCHAR(64)   NOT NULL,
    amount             DECIMAL(19,2) NULL,
    type               VARCHAR(16)   NULL,
    category_client_id VARCHAR(36)   NULL,
    account_client_id  VARCHAR(36)   NULL,
    note               VARCHAR(512)  NULL,
    sort_order         INT           NULL DEFAULT 0,
    last_modified      BIGINT        NOT NULL,
    deleted            TINYINT(1)    NOT NULL DEFAULT 0,
    created_at         DATETIME      NULL,
    updated_at         DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_client_id (client_id),
    KEY idx_template_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================
--  Demo Data（示例数据，便于下载者快速上手）
-- =============================================================

-- 账户
INSERT INTO accounts (client_id, name, type, icon, initial_balance, sort_order, last_modified, deleted, created_at, updated_at) VALUES
('acc-cash',      '现金',   'CASH',       '💵', 500.00,  0, 1750000000000, 0, NOW(), NOW()),
('acc-savings',   '储蓄卡', 'DEBIT_CARD', '🏦', 20000.00, 1, 1750000000000, 0, NOW(), NOW()),
('acc-credit',    '信用卡', 'CREDIT_CARD','💳', 0.00,    2, 1750000000000, 0, NOW(), NOW()),
('acc-wechat',    '微信',   'WECHAT',     '💚', 300.00,  3, 1750000000000, 0, NOW(), NOW()),
('acc-alipay',    '支付宝', 'ALIPAY',     '💙', 800.00,  4, 1750000000000, 0, NOW(), NOW());

-- 支出分类
INSERT INTO category (client_id, name, type, icon, sort_order, last_modified, deleted, created_at, updated_at) VALUES
('cat-food',   '餐饮', 'EXPENSE', '🍜', 0, 1750000000000, 0, NOW(), NOW()),
('cat-traffic','交通', 'EXPENSE', '🚌', 1, 1750000000000, 0, NOW(), NOW()),
('cat-shopping','购物','EXPENSE', '🛍️', 2, 1750000000000, 0, NOW(), NOW()),
('cat-housing', '住房', 'EXPENSE', '🏠', 3, 1750000000000, 0, NOW(), NOW()),
('cat-comms',   '通讯', 'EXPENSE', '📱', 4, 1750000000000, 0, NOW(), NOW()),
('cat-medical', '医疗', 'EXPENSE', '💊', 5, 1750000000000, 0, NOW(), NOW()),
('cat-edu',     '教育', 'EXPENSE', '📚', 6, 1750000000000, 0, NOW(), NOW()),
('cat-ent',     '娱乐', 'EXPENSE', '🎮', 7, 1750000000000, 0, NOW(), NOW()),
('cat-gift',    '人情', 'EXPENSE', '🎁', 8, 1750000000000, 0, NOW(), NOW()),
('cat-other',   '其他', 'EXPENSE', '📦', 9, 1750000000000, 0, NOW(), NOW());

-- 收入分类
INSERT INTO category (client_id, name, type, icon, sort_order, last_modified, deleted, created_at, updated_at) VALUES
('cat-salary',   '工资',     'INCOME', '💼', 0, 1750000000000, 0, NOW(), NOW()),
('cat-side',     '副业',     'INCOME', '🧾', 1, 1750000000000, 0, NOW(), NOW()),
('cat-donation', '红包捐赠', 'INCOME', '🧧', 2, 1750000000000, 0, NOW(), NOW());

-- 预算（示例：餐饮 2000，购物 1000）
INSERT INTO budget (client_id, category_client_id, budget_year, budget_month, amount_limit, last_modified, deleted, created_at, updated_at) VALUES
('bud-food', 'cat-food',   2026, 8, 2000.00, 1750000000000, 0, NOW(), NOW()),
('bud-shop', 'cat-shopping',2026, 8, 1000.00, 1750000000000, 0, NOW(), NOW());

-- 记账流水（示例几条）
INSERT INTO transaction_record (client_id, date, amount, type, category_client_id, account_client_id, note, source, last_modified, deleted, created_at, updated_at) VALUES
('txn-0001', '2026-08-01', 28.50, 'EXPENSE', 'cat-food', 'acc-wechat',   '早餐：豆浆油条', 'manual', 1750001000000, 0, NOW(), NOW()),
('txn-0002', '2026-08-02', 12.00, 'EXPENSE', 'cat-traffic','acc-alipay',  '地铁通勤', 'manual', 1750002000000, 0, NOW(), NOW()),
('txn-0003', '2026-08-03', 350.00, 'EXPENSE','cat-shopping','acc-credit', '优衣库T恤', 'manual', 1750003000000, 0, NOW(), NOW()),
('txn-0004', '2026-08-04', 45.00, 'EXPENSE', 'cat-food', 'acc-cash',     '朋友聚餐', 'manual', 1750004000000, 0, NOW(), NOW()),
('txn-0005', '2026-08-05', 12000.00, 'INCOME','cat-salary', 'acc-savings','工资', 'manual', 1750005000000, 0, NOW(), NOW()),
('txn-0006', '2026-08-06', 99.00, 'EXPENSE', 'cat-ent', 'acc-wechat',    '电影票x2', 'manual', 1750006000000, 0, NOW(), NOW());

-- 模板
INSERT INTO template (client_id, name, amount, type, category_client_id, account_client_id, note, sort_order, last_modified, deleted, created_at, updated_at) VALUES
('tpl-mortgage', '每月房贷', 3500.00, 'EXPENSE', 'cat-housing', 'acc-savings', '房贷自动记账', 0, 1750000000000, 0, NOW(), NOW()),
('tpl-lunch',    '工作日午餐', 25.00, 'EXPENSE', 'cat-food', 'acc-wechat',  null, 1, 1750000000000, 0, NOW(), NOW());
