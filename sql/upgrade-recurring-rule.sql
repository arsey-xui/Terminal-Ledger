-- =============================================================
--  升级脚本：给已有的库补 recurring_rule 表
-- -------------------------------------------------------------
--  什么时候需要跑
--    库是用 v1.2.0 之前的 sql/init.sql 建的（那份文件当时还没有这张表），
--    而后端默认 ddl-auto=none（见 application.yml 的 JPA_DDL_AUTO），不会自动建表。
--    这种库上任何一次同步都会报：Table 'accounting.recurring_rule' doesn't exist。
--
--  怎么跑
--    mysql -u root -p accounting < sql/upgrade-recurring-rule.sql
--
--  可以重复执行
--    用的是 CREATE TABLE IF NOT EXISTS，已经建过这张表的库不受影响。
--    全新部署不需要跑这个文件，sql/init.sql 里已经包含这张表。
-- =============================================================

CREATE TABLE IF NOT EXISTS recurring_rule (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    client_id           VARCHAR(36)   NOT NULL,
    name                VARCHAR(64)   NOT NULL,
    amount              DECIMAL(19,2) NOT NULL,
    -- 实体里 type 映射到的列名是 txn_type（@Column(name = "txn_type")），别写成 type
    txn_type            VARCHAR(16)   NOT NULL,
    category_client_id  VARCHAR(36)   NULL,
    account_client_id   VARCHAR(36)   NULL,
    note                VARCHAR(512)  NULL,
    interval_unit       VARCHAR(8)    NOT NULL DEFAULT 'MONTH',
    interval_count      INT           NOT NULL DEFAULT 1,
    anchor_date         DATE          NOT NULL,
    last_generated_date DATE          NULL,
    enabled             TINYINT(1)    NOT NULL DEFAULT 1,
    sort_order          INT           NULL DEFAULT 0,
    last_modified       BIGINT        NOT NULL,
    deleted             TINYINT(1)    NOT NULL DEFAULT 0,
    created_at          DATETIME      NULL,
    updated_at          DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recurring_rule_client_id (client_id),
    KEY idx_recurring_last_modified (last_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
