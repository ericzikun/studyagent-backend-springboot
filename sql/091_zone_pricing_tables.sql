-- Zone pricing (plan A, DB-backed): pricing zones, country routes, immutable
-- Stripe Price registry, legacy price aliases, and next-cycle rollout ledger.
-- Companion seed migration: 092_zone_pricing_seed.sql (generated from
-- docs/billing-regional-pricing/regional-expansion-prices.json).
--
-- Idempotent — safe to re-run on Sandbox and Production. All switches ship
-- OFF; deploying this migration changes no behavior until rows are backfilled
-- and enabled.

USE studyagent;

-- Force utf8mb4: the mysql CLI may default to latin1 and double-encode Chinese comments.
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. pricing_zones: zone definition + current sale version pointer
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pricing_zones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code VARCHAR(8) NOT NULL COMMENT '定价区，如 Z080',
    label VARCHAR(255) NOT NULL COMMENT '倍率区与国家清单（来自 Price nickname，审计展示）',
    reference_factor DECIMAL(4,2) NOT NULL COMMENT '参考倍率，仅审计展示，运行时不参与计算',
    active_version VARCHAR(32) NOT NULL COMMENT '当前新购销售版本，如 regional-20261018-v1',
    is_active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '区级销售开关',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pz_zone (zone_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：定价区定义与当前销售版本';

-- Older drafts used VARCHAR(64); widen idempotently so the 17-band labels fit.
SET @zp_ddl = 'ALTER TABLE pricing_zones MODIFY COLUMN label VARCHAR(255) NOT NULL COMMENT ''倍率区与国家清单（来自 Price nickname，审计展示）''';
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

-- ---------------------------------------------------------------------------
-- 2. pricing_country_routes: country -> zone/currency routing + enable list
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pricing_country_routes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    country_code CHAR(2) NOT NULL COMMENT 'ISO 3166-1 alpha-2 大写，与可信国家头同格式',
    zone_code VARCHAR(8) NOT NULL,
    currency CHAR(3) NOT NULL COMMENT '本币小写，如 eur/hkd',
    label VARCHAR(64) NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '启用前置：该区 11 个 SKU 价格齐备且预检通过',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pcr_country (country_code),
    KEY idx_pcr_zone (zone_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：国家→区/币种路由，is_enabled=启用名单';

-- ---------------------------------------------------------------------------
-- 3. pricing_zone_prices: immutable Price registry (zone x version x sku)
--    Forward lookup: pricing selection. Reverse lookup (by stripe_price_id):
--    webhook / contract resolution. stripe_price_id stays NULL until the
--    sandbox Price is created and backfilled (one-time backfill; never
--    re-pointed afterwards).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pricing_zone_prices (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_code VARCHAR(8) NOT NULL,
    price_version VARCHAR(32) NOT NULL,
    sku_code VARCHAR(64) NOT NULL COMMENT 'plan_code / addon_code / study_pass SKU',
    sku_kind VARCHAR(32) NOT NULL COMMENT 'subscription_plan | addon | study_pass',
    stripe_price_id VARCHAR(128) NULL COMMENT 'NULL=未配置不可售；回填后永不改指向',
    stripe_product_id VARCHAR(128) NULL COMMENT '必须等于该 SKU 现有 Product',
    price_type VARCHAR(16) NOT NULL COMMENT 'recurring | one_time',
    billing_interval VARCHAR(8) NULL COMMENT 'week | month | year；one_time 为 NULL',
    interval_count INT NOT NULL DEFAULT 1,
    usd_default_amount_minor INT NOT NULL COMMENT '该 Price 的 USD 技术默认金额，非销售入口',
    currency_amounts JSON NOT NULL COMMENT '{"eur":{"amountMinor":1399}} 预检核对基准；实际金额仍从 Stripe 实读比对',
    renewal_sku_code VARCHAR(64) NULL COMMENT '试用 SKU 的转正目标 SKU',
    is_active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=绑定作废（注册表不可变，用停用代替删除）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pzp_binding (zone_code, price_version, sku_code),
    UNIQUE KEY uk_pzp_price (stripe_price_id),
    KEY idx_pzp_sku (sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='区域定价：不可变 Price 注册表，正查选价、反查解析合同';

-- ---------------------------------------------------------------------------
-- 4. stripe_price_aliases: legacy Price ids outside the three catalog tables
--    (insurance layer; ships empty, filled only if read-only inventory finds
--    orphan ids)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stripe_price_aliases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stripe_price_id VARCHAR(128) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_kind VARCHAR(32) NOT NULL,
    note VARCHAR(255) NULL COMMENT '来源：盘点批次等',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_spa_price (stripe_price_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='历史 Price 别名：三大商品表之外的旧 Price 反查兜底';

-- BaseEntity always selects updated_at; add it for tables created by the earlier draft.
SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='stripe_price_aliases' AND column_name='updated_at')=0,
  'ALTER TABLE stripe_price_aliases ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

-- Earlier drafts used VARCHAR(16); 'subscription_plan' needs 17 chars.
SET @zp_ddl = 'ALTER TABLE pricing_zone_prices MODIFY COLUMN sku_kind VARCHAR(32) NOT NULL COMMENT ''subscription_plan | addon | study_pass''';
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = 'ALTER TABLE stripe_price_aliases MODIFY COLUMN sku_kind VARCHAR(32) NOT NULL';
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

-- ---------------------------------------------------------------------------
-- 5. subscription_price_rollouts: next-cycle price-change execution ledger
--    dry-run also persists rows (status=preview/conflict/skipped).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscription_price_rollouts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL COMMENT '如 rollout-Z080-v2-20261101',
    contract_kind VARCHAR(16) NOT NULL COMMENT 'subscription | study_pass',
    clerk_user_id VARCHAR(64) NOT NULL,
    stripe_subscription_id VARCHAR(128) NOT NULL,
    zone_code VARCHAR(8) NOT NULL,
    -- skipped/conflict 行没有解析出价格/币种，这几列必须可空
    currency CHAR(3) NULL,
    source_price_id VARCHAR(128) NULL,
    source_version VARCHAR(32) NULL,
    target_price_id VARCHAR(128) NULL,
    target_version VARCHAR(32) NOT NULL,
    effective_at DATETIME NOT NULL COMMENT '计划生效点=当前付费周期结束',
    status VARCHAR(24) NOT NULL COMMENT 'preview|pending|scheduled|conflict|skipped|failed|superseded|cancelled|applied',
    status_reason VARCHAR(512) NULL COMMENT '跳过/冲突原因：已取消、已出账、归属不明、币种无目标…',
    snapshot_json JSON NULL COMMENT '写入前合同快照：schedule_id、cancel_at_period_end、period_end、发票状态',
    idempotency_key VARCHAR(180) NOT NULL COMMENT 'rollout:{batch}:{sub}:{targetPrice}:{effectiveAt}',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_spr_idem (idempotency_key),
    UNIQUE KEY uk_spr_batch_contract (batch_id, stripe_subscription_id),
    KEY idx_spr_status (status),
    KEY idx_spr_user (clerk_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='存量合同下周期换价：批次与逐合同执行记录（dry-run 亦落库）';

-- ---------------------------------------------------------------------------
-- 6. Contract zone columns (progressive backfill; NULL = legacy contract)
-- ---------------------------------------------------------------------------
SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='pricing_zone')=0,
  'ALTER TABLE user_subscriptions ADD COLUMN pricing_zone VARCHAR(8) NULL COMMENT ''合同定价区；NULL=legacy 合同''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='price_version')=0,
  'ALTER TABLE user_subscriptions ADD COLUMN price_version VARCHAR(32) NULL COMMENT ''当前生效价格版本''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='currency')=0,
  'ALTER TABLE user_subscriptions ADD COLUMN currency CHAR(3) NULL COMMENT ''实际扣款币种=Subscription.currency，小写''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='pricing_zone')=0,
  'ALTER TABLE study_passes ADD COLUMN pricing_zone VARCHAR(8) NULL COMMENT ''合同定价区；NULL=legacy 合同''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='price_version')=0,
  'ALTER TABLE study_passes ADD COLUMN price_version VARCHAR(32) NULL COMMENT ''当前生效价格版本''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

-- ---------------------------------------------------------------------------
-- 7. Purchase country (ISO alpha-2): the trusted geo used at checkout.
--    Contract actions price from pricing_zone/version, never from the current
--    request header, so a user switching IP keeps the purchased region.
-- ---------------------------------------------------------------------------
SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_subscriptions' AND column_name='pricing_country')=0,
  'ALTER TABLE user_subscriptions ADD COLUMN pricing_country CHAR(2) NULL COMMENT ''购买时可信国家（ISO 3166-1 alpha-2）''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;

SET @zp_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='study_passes' AND column_name='pricing_country')=0,
  'ALTER TABLE study_passes ADD COLUMN pricing_country CHAR(2) NULL COMMENT ''购买时可信国家（ISO 3166-1 alpha-2）''', 'SELECT 1');
PREPARE zp_stmt FROM @zp_ddl; EXECUTE zp_stmt; DEALLOCATE PREPARE zp_stmt;
