#!/usr/bin/env bash
set -Eeuo pipefail

# Start the local Spring Boot backend with the Java-side MockPy consumer enabled.
# This script owns only the local smoke environment: Docker MySQL/RabbitMQ plus
# agent-start. It does not start the real Python V2 agent, because MockPy consumes
# the same RabbitMQ command queues and would compete with it.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERLA_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
DEPS_DIR="${VERLA_ROOT}/studyagent-backend"
COMPOSE_FILE="${DEPS_DIR}/docker-cp/docker-compose.yml"
AGENT_START_DIR="${SCRIPT_DIR}/agent-start"

PORT="${PORT:-8080}"
SPRING_PROFILE="${SPRING_PROFILE:-local}"
START_DEPS="${START_DEPS:-true}"
BUILD_FIRST="${BUILD_FIRST:-true}"
PATCH_MOCK_DB="${PATCH_MOCK_DB:-true}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-13306}"
DB_NAME="${DB_NAME:-studyagent}"
DB_USERNAME="${DB_USERNAME:-studyagent}"
DB_PASSWORD="${DB_PASSWORD:-studyagent2024}"
DB_CONTAINER="${DB_CONTAINER:-studyagent-mysql}"

RABBITMQ_HOST="${RABBITMQ_HOST:-localhost}"
RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
RABBITMQ_MGMT_PORT="${RABBITMQ_MGMT_PORT:-15672}"
RABBITMQ_USERNAME="${RABBITMQ_USERNAME:-studyagent}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-studyagent2024}"
RABBITMQ_VHOST="${RABBITMQ_VHOST:-/}"

VERLA_MOCK_DELAY_MS="${VERLA_MOCK_DELAY_MS:-50}"
BILLING_PORTAL_MOCK_ENABLED="${BILLING_PORTAL_MOCK_ENABLED:-true}"
BILLING_PORTAL_MOCK_URL="${BILLING_PORTAL_MOCK_URL:-return-url}"
BILLING_CHECKOUT_MOCK_ENABLED="${BILLING_CHECKOUT_MOCK_ENABLED:-true}"
PAYMENT_CHECKOUT_MOCK_ENABLED="${PAYMENT_CHECKOUT_MOCK_ENABLED:-${BILLING_CHECKOUT_MOCK_ENABLED}}"

if [[ "${BILLING_PORTAL_MOCK_ENABLED}" != "true" ]]; then
  BILLING_PORTAL_MOCK_URL=""
fi

compose_cmd=()
if docker compose version >/dev/null 2>&1; then
  compose_cmd=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose_cmd=(docker-compose)
fi

run_mysql() {
  if command -v docker >/dev/null 2>&1 && docker container inspect "${DB_CONTAINER}" >/dev/null 2>&1; then
    docker exec -i \
      -e MYSQL_PWD="${DB_PASSWORD}" \
      "${DB_CONTAINER}" \
      mysql \
      -u "${DB_USERNAME}" \
      "${DB_NAME}" \
      "$@"
    return
  fi

  if command -v mysql >/dev/null 2>&1; then
    MYSQL_PWD="${DB_PASSWORD}" mysql \
      --protocol=TCP \
      -h "${DB_HOST}" \
      -P "${DB_PORT}" \
      -u "${DB_USERNAME}" \
      "${DB_NAME}" \
      "$@"
    return
  fi

  echo "ERROR: mysql client not found and Docker container '${DB_CONTAINER}' is not running." >&2
  return 1
}

wait_for_mysql() {
  local attempt
  for attempt in {1..30}; do
    if run_mysql -N -B -e "SELECT 1" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "ERROR: MySQL is not ready: ${DB_HOST}:${DB_PORT}/${DB_NAME}" >&2
  return 1
}

wait_for_rabbitmq_management() {
  local attempt
  for attempt in {1..30}; do
    if curl -fsS \
      -u "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
      "http://${RABBITMQ_HOST}:${RABBITMQ_MGMT_PORT}/api/overview" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "WARN: RabbitMQ management API is not ready: ${RABBITMQ_HOST}:${RABBITMQ_MGMT_PORT}" >&2
  return 1
}

rabbit_vhost_path() {
  if [[ "${RABBITMQ_VHOST}" == "/" ]]; then
    printf '%%2F'
  else
    printf '%s' "${RABBITMQ_VHOST//\//%2F}"
  fi
}

ensure_rabbit_binding() {
  local exchange="$1"
  local queue="$2"
  local routing_key="$3"
  local vhost_path
  vhost_path="$(rabbit_vhost_path)"

  curl -fsS \
    -u "${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}" \
    -H 'content-type: application/json' \
    -X POST \
    "http://${RABBITMQ_HOST}:${RABBITMQ_MGMT_PORT}/api/bindings/${vhost_path}/e/${exchange}/q/${queue}" \
    -d "{\"routing_key\":\"${routing_key}\",\"arguments\":{}}" >/dev/null
}

ensure_mock_rabbit_topology() {
  if ! wait_for_rabbitmq_management; then
    return
  fi

  # Local RabbitMQ may keep an older Verla topology where RabbitAdmin stops at a
  # DLX exchange type mismatch before declaring newer command bindings. MockPy
  # still needs these routes so file chat and slide conversion commands can be
  # consumed by the shared Java mock agent queue.
  echo "Ensuring Spring Boot mock RabbitMQ command bindings"
  ensure_rabbit_binding "studyagent.command" "verla.cmd.agent" "cmd.file.chat"
  ensure_rabbit_binding "studyagent.command" "verla.cmd.agent" "cmd.slides.convert_to_editor_json"
}

column_value() {
  local table="$1"
  local column="$2"
  local field="$3"

  run_mysql -N -B -e "
    SELECT ${field}
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = '${DB_NAME}'
      AND TABLE_NAME = '${table}'
      AND COLUMN_NAME = '${column}'
    LIMIT 1;
  " | tr -d '[:space:]'
}

column_exists() {
  [[ -n "$(column_value "$1" "$2" "COLUMN_NAME")" ]]
}

table_exists() {
  local table="$1"
  [[ -n "$(run_mysql -N -B -e "
    SELECT TABLE_NAME
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${DB_NAME}'
      AND TABLE_NAME = '${table}'
    LIMIT 1;
  " | tr -d '[:space:]')" ]]
}

index_exists() {
  local table="$1"
  local index="$2"
  [[ -n "$(run_mysql -N -B -e "
    SELECT INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${DB_NAME}'
      AND TABLE_NAME = '${table}'
      AND INDEX_NAME = '${index}'
    LIMIT 1;
  " | tr -d '[:space:]')" ]]
}

apply_mq_outbox_verla_columns() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`mq_outbox\`
    ADD COLUMN correlation_id  VARCHAR(160) DEFAULT NULL  COMMENT 'conv:{cid}:turn:{tid}:sess:{sid}'                        AFTER payload,
    ADD COLUMN ordering_key    VARCHAR(64)  DEFAULT NULL  COMMENT 'session:{sessionId}'                                      AFTER correlation_id,
    ADD COLUMN schema_version  INT          NOT NULL DEFAULT 1 COMMENT '信封 schema 版本'                                    AFTER ordering_key,
    ADD COLUMN conversation_id BIGINT       DEFAULT NULL  COMMENT 'Verla conversation id（老链路为 NULL）'                   AFTER schema_version,
    ADD COLUMN turn_id         BIGINT       DEFAULT NULL  COMMENT 'Verla turn id（老链路为 NULL）'                            AFTER conversation_id,
    ADD COLUMN session_id      BIGINT       DEFAULT NULL  COMMENT 'Verla session id（老链路为 NULL）'                         AFTER turn_id,
    ADD COLUMN exchange        VARCHAR(64)  DEFAULT NULL  COMMENT '目标 exchange（NULL 走默认）'                              AFTER session_id,
    ADD COLUMN routing_key     VARCHAR(160) DEFAULT NULL  COMMENT '路由键；与 action 不同，可独立指定'                        AFTER exchange,
    ADD INDEX idx_correlation (correlation_id),
    ADD INDEX idx_session     (session_id);
SQL
}

apply_mq_outbox_task_id_nullable() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`mq_outbox\`
    MODIFY COLUMN task_id BIGINT NULL COMMENT '老链路 Task.id；Verla 命令为 NULL';
SQL
}

apply_mq_outbox_claim_columns() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`mq_outbox\`
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '0=UNSENT, 1=SENT, 2=FAILED, 3=SENDING',
    ADD COLUMN worker_id       VARCHAR(128) DEFAULT NULL COMMENT '当前 claim / sending worker' AFTER error_message,
    ADD COLUMN lease_until     DATETIME     DEFAULT NULL COMMENT '当前 claim lease 截止时间' AFTER worker_id,
    ADD COLUMN last_claimed_at DATETIME     DEFAULT NULL COMMENT '最近一次 claim 时间' AFTER lease_until,
    ADD INDEX idx_mq_outbox_claim (status, next_retry_at, lease_until, id),
    ADD INDEX idx_mq_outbox_worker (worker_id, status, lease_until);
SQL
}

apply_user_profiles_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`user_profiles\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`clerk_user_id\` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Clerk用户ID（唯一标识）',
  \`display_name\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '显示名称（可覆盖Clerk的）',
  \`locale\` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT 'en' COMMENT '语言偏好',
  \`is_admin\` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否为管理员',
  \`is_active\` tinyint(1) NOT NULL DEFAULT '1' COMMENT '账户是否激活',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`updated_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_user_profiles_clerk_user_id\` (\`clerk_user_id\`),
  KEY \`idx_user_profiles_clerk_user_id\` (\`clerk_user_id\`),
  KEY \`idx_user_profiles_is_admin\` (\`is_admin\`),
  KEY \`idx_user_profiles_is_active\` (\`is_active\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户业务信息表（关联Clerk）';
SQL
}

apply_ai_feature_defs_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`ai_feature_defs\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`feature_code\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '功能编码',
  \`feature_name\` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '功能名称',
  \`quota_unit\` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'count' COMMENT '额度单位: count / words',
  \`free_quota_period\` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'monthly' COMMENT '免费额度周期',
  \`free_quota_amount\` bigint NOT NULL DEFAULT 0 COMMENT '每周期免费额度',
  \`is_active\` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  \`display_order\` int NOT NULL DEFAULT 0 COMMENT '展示顺序',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`updated_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_ai_feature_defs_code\` (\`feature_code\`),
  KEY \`idx_ai_feature_defs_active_order\` (\`is_active\`, \`display_order\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 功能额度定义';
SQL
}

apply_ai_feature_packages_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`ai_feature_packages\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`feature_code\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '功能编码',
  \`package_code\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐编码',
  \`package_name\` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐名称',
  \`quota_amount\` bigint NOT NULL DEFAULT 0 COMMENT '充值额度',
  \`price_cents\` int NOT NULL DEFAULT 0 COMMENT '价格（美分）',
  \`currency\` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'usd' COMMENT '币种',
  \`stripe_price_id\` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe price id',
  \`stripe_product_id\` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe product id',
  \`is_active\` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  \`display_order\` int NOT NULL DEFAULT 0 COMMENT '展示顺序',
  \`label\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '展示标签',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`updated_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_ai_feature_packages_code\` (\`feature_code\`, \`package_code\`),
  KEY \`idx_ai_feature_packages_active_order\` (\`feature_code\`, \`is_active\`, \`display_order\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 功能充值套餐定义';
SQL
}

apply_user_ai_quotas_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`user_ai_quotas\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`clerk_user_id\` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Clerk 用户ID',
  \`feature_code\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '功能编码',
  \`free_balance\` bigint NOT NULL DEFAULT 0 COMMENT '免费额度余额',
  \`free_period_start\` datetime DEFAULT NULL COMMENT '免费周期开始时间',
  \`free_period_end\` datetime DEFAULT NULL COMMENT '免费周期结束时间',
  \`plan_balance\` bigint NOT NULL DEFAULT 0 COMMENT '订阅套餐额度余额',
  \`plan_period_start\` datetime DEFAULT NULL COMMENT '订阅套餐额度周期开始时间',
  \`plan_period_end\` datetime DEFAULT NULL COMMENT '订阅套餐额度周期结束时间',
  \`paid_balance\` bigint NOT NULL DEFAULT 0 COMMENT '付费额度余额',
  \`version\` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`updated_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_user_ai_quotas_user_feature\` (\`clerk_user_id\`, \`feature_code\`),
  KEY \`idx_user_ai_quotas_feature\` (\`feature_code\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户 AI 额度余额';
SQL
}

apply_quota_ledger_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`quota_ledger\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`ledger_no\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流水号',
  \`clerk_user_id\` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Clerk 用户ID',
  \`feature_code\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '功能编码',
  \`ledger_type\` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'consume / refund / recharge',
  \`amount\` bigint NOT NULL COMMENT '流水额度，扣减为负数',
  \`source_type\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源类型',
  \`source_id\` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源ID',
  \`idempotency_key\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务幂等键',
  \`subscription_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe subscription id',
  \`invoice_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe invoice id',
  \`free_balance_after\` bigint DEFAULT NULL COMMENT '变更后免费余额',
  \`plan_balance_after\` bigint DEFAULT NULL COMMENT '变更后订阅套餐余额',
  \`addon_balance_after\` bigint DEFAULT NULL COMMENT '变更后 add-on 汇总余额',
  \`paid_balance_after\` bigint DEFAULT NULL COMMENT '变更后付费余额',
  \`biz_context\` json DEFAULT NULL COMMENT '业务上下文',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_quota_ledger_no\` (\`ledger_no\`),
  UNIQUE KEY \`uk_quota_ledger_feature_type_idempotency\` (\`feature_code\`, \`ledger_type\`, \`idempotency_key\`),
  KEY \`idx_quota_ledger_user_feature\` (\`clerk_user_id\`, \`feature_code\`, \`created_at\`),
  KEY \`idx_quota_ledger_source\` (\`source_type\`, \`source_id\`),
  KEY \`idx_quota_ledger_type\` (\`ledger_type\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 额度流水';
SQL
}

apply_recharge_orders_table() {
  run_mysql <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`recharge_orders\` (
  \`id\` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  \`order_no\` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
  \`order_type\` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy_recharge' COMMENT 'legacy_recharge / subscription / addon',
  \`clerk_user_id\` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Clerk 用户ID',
  \`feature_code\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '功能编码',
  \`package_code\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '旧额度包编码',
  \`plan_code\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订阅套餐编码',
  \`target_plan_code\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '升级目标订阅套餐编码',
  \`addon_code\` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'add-on 编码',
  \`quota_amount\` bigint NOT NULL DEFAULT 0 COMMENT '到账额度',
  \`price_cents\` int NOT NULL DEFAULT 0 COMMENT '价格（美分）',
  \`quoted_amount_cents\` int DEFAULT NULL COMMENT '报价金额（美分）',
  \`upgrade_charge_type\` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '升级收费类型',
  \`currency\` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'usd' COMMENT '币种',
  \`stripe_session_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe checkout session id',
  \`stripe_payment_intent_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe payment intent id',
  \`stripe_invoice_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe invoice id',
  \`stripe_subscription_id\` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe subscription id',
  \`status\` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT 'pending / completed / failed',
  \`failure_reason\` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  \`paid_at\` datetime DEFAULT NULL COMMENT '支付完成时间',
  \`upgrade_effective_at\` datetime DEFAULT NULL COMMENT '升级生效时间',
  \`switch_attempts\` int NOT NULL DEFAULT 0 COMMENT '切换重试次数',
  \`biz_context\` json DEFAULT NULL COMMENT '业务上下文',
  \`created_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`updated_at\` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`uk_recharge_order_no\` (\`order_no\`),
  UNIQUE KEY \`uk_recharge_stripe_session\` (\`stripe_session_id\`),
  UNIQUE KEY \`uk_recharge_stripe_invoice\` (\`stripe_invoice_id\`),
  KEY \`idx_recharge_user_status\` (\`clerk_user_id\`, \`status\`, \`created_at\`),
  KEY \`idx_recharge_subscription\` (\`stripe_subscription_id\`, \`created_at\`),
  KEY \`idx_recharge_order_type_status\` (\`order_type\`, \`status\`, \`created_at\`),
  KEY \`idx_recharge_upgrade_user_status\` (\`clerk_user_id\`, \`order_type\`, \`status\`, \`created_at\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商业化充值/订阅订单';
SQL
}

seed_mock_quota_features() {
  run_mysql <<SQL
INSERT INTO \`${DB_NAME}\`.\`ai_feature_defs\`
  (\`feature_code\`, \`feature_name\`, \`quota_unit\`, \`free_quota_period\`, \`free_quota_amount\`, \`is_active\`, \`display_order\`, \`created_at\`, \`updated_at\`)
VALUES
  ('task_create', 'Assignment', 'count', 'monthly', 1, 1, 10, NOW(), NOW()),
  ('ai_detection', 'AI Detection', 'count', 'monthly', 1, 1, 20, NOW(), NOW()),
  ('humanizer', 'Humanizer', 'count', 'monthly', 1, 1, 30, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  \`feature_name\` = VALUES(\`feature_name\`),
  \`quota_unit\` = VALUES(\`quota_unit\`),
  \`free_quota_period\` = VALUES(\`free_quota_period\`),
  \`free_quota_amount\` = VALUES(\`free_quota_amount\`),
  \`is_active\` = VALUES(\`is_active\`),
  \`display_order\` = VALUES(\`display_order\`),
  \`updated_at\` = NOW();

INSERT INTO \`${DB_NAME}\`.\`ai_feature_packages\`
  (\`feature_code\`, \`package_code\`, \`package_name\`, \`quota_amount\`, \`price_cents\`, \`currency\`, \`is_active\`, \`display_order\`, \`label\`, \`created_at\`, \`updated_at\`)
VALUES
  ('task_create', 'assignment_1', '1 Assignment Credit', 1, 299, 'usd', 1, 10, 'normal', NOW(), NOW()),
  ('task_create', 'assignment_5', '5 Assignment Credits', 5, 1299, 'usd', 1, 11, 'normal', NOW(), NOW()),
  ('task_create', 'assignment_10', '10 Assignment Credits', 10, 2399, 'usd', 1, 12, 'popular', NOW(), NOW()),
  ('task_create', 'assignment_50', '50 Assignment Credits', 50, 9999, 'usd', 1, 13, 'best', NOW(), NOW()),
  ('ai_detection', 'detection_10k', '10,000 AI Detection Words', 10000, 199, 'usd', 1, 20, 'normal', NOW(), NOW()),
  ('ai_detection', 'detection_50k', '50,000 AI Detection Words', 50000, 799, 'usd', 1, 21, 'normal', NOW(), NOW()),
  ('ai_detection', 'detection_200k', '200,000 AI Detection Words', 200000, 2399, 'usd', 1, 22, 'best', NOW(), NOW()),
  ('humanizer', 'humanizer_10k', '10,000 Humanizer Words', 10000, 299, 'usd', 1, 30, 'normal', NOW(), NOW()),
  ('humanizer', 'humanizer_50k', '50,000 Humanizer Words', 50000, 1199, 'usd', 1, 31, 'normal', NOW(), NOW()),
  ('humanizer', 'humanizer_200k', '200,000 Humanizer Words', 200000, 3999, 'usd', 1, 32, 'best', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  \`package_name\` = VALUES(\`package_name\`),
  \`quota_amount\` = VALUES(\`quota_amount\`),
  \`price_cents\` = VALUES(\`price_cents\`),
  \`currency\` = VALUES(\`currency\`),
  \`is_active\` = VALUES(\`is_active\`),
  \`display_order\` = VALUES(\`display_order\`),
  \`label\` = VALUES(\`label\`),
  \`updated_at\` = NOW();
SQL
}

apply_verla_attachments_attachment_origin_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_attachments\`
    ADD COLUMN attachment_origin VARCHAR(32) NOT NULL DEFAULT 'USER_UPLOAD' COMMENT 'USER_UPLOAD / AGENT_OUTPUT' AFTER primary_artifact_uid;
SQL
}

apply_verla_sessions_quota_ledger_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_sessions\`
    ADD COLUMN quota_ledger_id BIGINT NULL COMMENT '本 session 扣费流水 ID（refund 索引）' AFTER feature_code;
SQL
}

apply_verla_sessions_quota_amount_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_sessions\`
    ADD COLUMN quota_amount BIGINT NULL COMMENT '本 session 扣费数量（次或字，仅记录便于排错）' AFTER quota_ledger_id;
SQL
}

apply_verla_sessions_quota_ledger_id_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_sessions\`
    ADD INDEX idx_quota_ledger_id (quota_ledger_id);
SQL
}

apply_humanizer_tasks_quota_ledger_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`humanizer_tasks\`
    ADD COLUMN quota_ledger_id BIGINT NULL COMMENT 'Quota ledger ID for refund on failure' AFTER retry_count;
SQL
}

apply_humanizer_tasks_total_words_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`humanizer_tasks\`
    ADD COLUMN total_words INT NOT NULL DEFAULT 0 COMMENT 'Total word count of input text' AFTER quota_ledger_id;
SQL
}

apply_humanizer_tasks_consumed_words_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`humanizer_tasks\`
    ADD COLUMN consumed_words INT NOT NULL DEFAULT 0 COMMENT 'Words consumed (quota deducted) so far, for streaming per-chunk billing' AFTER total_words;
SQL
}

apply_user_ai_quotas_plan_balance_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_ai_quotas\`
    ADD COLUMN plan_balance BIGINT NOT NULL DEFAULT 0 COMMENT 'Current subscription plan balance for this feature' AFTER free_period_end;
SQL
}

apply_user_ai_quotas_plan_period_start_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_ai_quotas\`
    ADD COLUMN plan_period_start DATETIME NULL COMMENT 'Current plan quota period start' AFTER plan_balance;
SQL
}

apply_user_ai_quotas_plan_period_end_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_ai_quotas\`
    ADD COLUMN plan_period_end DATETIME NULL COMMENT 'Current plan quota period end' AFTER plan_period_start;
SQL
}

apply_quota_ledger_idempotency_key_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`quota_ledger\`
    ADD COLUMN idempotency_key VARCHAR(255) NULL COMMENT 'Business idempotency key for grants / clears / refunds' AFTER source_id;
SQL
}

apply_quota_ledger_subscription_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`quota_ledger\`
    ADD COLUMN subscription_id VARCHAR(255) NULL COMMENT 'Stripe subscription id for subscription-driven ledger entries' AFTER idempotency_key;
SQL
}

apply_quota_ledger_invoice_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`quota_ledger\`
    ADD COLUMN invoice_id VARCHAR(255) NULL COMMENT 'Stripe invoice id for invoice-driven ledger entries' AFTER subscription_id;
SQL
}

apply_quota_ledger_plan_balance_after_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`quota_ledger\`
    ADD COLUMN plan_balance_after BIGINT NULL COMMENT 'Plan pool balance snapshot after this ledger row' AFTER free_balance_after;
SQL
}

apply_quota_ledger_addon_balance_after_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`quota_ledger\`
    ADD COLUMN addon_balance_after BIGINT NULL COMMENT 'Aggregated add-on pool balance snapshot after this ledger row' AFTER plan_balance_after;
SQL
}

apply_quota_ledger_idempotency_index() {
  run_mysql <<SQL
CREATE UNIQUE INDEX uk_quota_ledger_feature_type_idempotency
    ON \`${DB_NAME}\`.\`quota_ledger\` (feature_code, ledger_type, idempotency_key);
SQL
}

apply_recharge_orders_order_type_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN order_type VARCHAR(32) NOT NULL DEFAULT 'legacy_recharge' AFTER order_no;
SQL
}

apply_recharge_orders_plan_code_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN plan_code VARCHAR(64) NULL AFTER package_code;
SQL
}

apply_recharge_orders_addon_code_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN addon_code VARCHAR(64) NULL AFTER plan_code;
SQL
}

apply_recharge_orders_target_plan_code_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN target_plan_code VARCHAR(64) NULL AFTER plan_code;
SQL
}

apply_recharge_orders_quoted_amount_cents_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN quoted_amount_cents INT NULL AFTER price_cents;
SQL
}

apply_recharge_orders_upgrade_charge_type_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN upgrade_charge_type VARCHAR(32) NULL AFTER target_plan_code;
SQL
}

apply_recharge_orders_stripe_invoice_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN stripe_invoice_id VARCHAR(255) NULL AFTER stripe_payment_intent_id;
SQL
}

apply_recharge_orders_stripe_subscription_id_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN stripe_subscription_id VARCHAR(255) NULL AFTER stripe_invoice_id;
SQL
}

apply_recharge_orders_upgrade_effective_at_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN upgrade_effective_at DATETIME NULL AFTER paid_at;
SQL
}

apply_recharge_orders_switch_attempts_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN switch_attempts INT NOT NULL DEFAULT 0 AFTER upgrade_effective_at;
SQL
}

apply_recharge_orders_biz_context_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD COLUMN biz_context JSON NULL AFTER switch_attempts;
SQL
}

apply_recharge_orders_stripe_invoice_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD UNIQUE KEY uk_recharge_stripe_invoice (stripe_invoice_id);
SQL
}

apply_recharge_orders_subscription_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD INDEX idx_recharge_subscription (stripe_subscription_id, created_at);
SQL
}

apply_recharge_orders_order_type_status_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD INDEX idx_recharge_order_type_status (order_type, status, created_at);
SQL
}

apply_recharge_orders_upgrade_user_status_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`recharge_orders\`
    ADD INDEX idx_recharge_upgrade_user_status (clerk_user_id, order_type, status, created_at);
SQL
}

apply_user_subscriptions_pending_upgrade_order_no_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_subscriptions\`
    ADD COLUMN pending_upgrade_order_no VARCHAR(64) NULL AFTER pending_effective_at;
SQL
}

apply_user_subscriptions_pending_upgrade_expires_at_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_subscriptions\`
    ADD COLUMN pending_upgrade_expires_at DATETIME NULL AFTER pending_upgrade_order_no;
SQL
}

apply_user_subscriptions_pending_upgrade_index() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`user_subscriptions\`
    ADD INDEX idx_user_subscription_pending_upgrade (pending_upgrade_order_no, pending_upgrade_expires_at);
SQL
}

apply_verla_workforce_tasks_compose_current_round_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_workforce_tasks\`
    ADD COLUMN compose_current_round SMALLINT DEFAULT NULL
        COMMENT 'compose 节点：当前已完成的 compose 轮次'
        AFTER plan_task_count;
SQL
}

apply_verla_workforce_tasks_compose_total_rounds_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_workforce_tasks\`
    ADD COLUMN compose_total_rounds SMALLINT DEFAULT NULL
        COMMENT 'compose / plan 节点：compose 总轮次'
        AFTER compose_current_round;
SQL
}

apply_verla_workforce_tasks_node_kind_comment() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_workforce_tasks\`
    MODIFY COLUMN node_kind VARCHAR(16) NOT NULL DEFAULT 'task'
        COMMENT 'plan / task / compose';
SQL
}

apply_sql_file() {
  local file="$1"
  # Migration files in this repo often contain "USE studyagent;" because the
  # canonical local DB is named studyagent. start-mock can be pointed at another
  # DB_NAME, so execute those files against the already-selected connection DB.
  sed '/^USE studyagent;$/d' "${file}" | run_mysql
}

ensure_mock_db_schema() {
  if [[ "${PATCH_MOCK_DB}" != "true" ]]; then
    echo "Skipping mock DB schema patch because PATCH_MOCK_DB=${PATCH_MOCK_DB}"
    return
  fi

  echo "Checking Spring Boot mock DB schema"
  wait_for_mysql

  # Java MockPy smoke still calls auth endpoints; keep the minimal Clerk user table
  # available even when the shared Python DB init did not run.
  if ! table_exists "user_profiles"; then
    echo "Creating user_profiles table for local auth mock"
    apply_user_profiles_table
  fi

  if ! table_exists "ai_feature_defs"; then
    echo "Creating ai_feature_defs table for local quota mock"
    apply_ai_feature_defs_table
  fi
  if ! table_exists "ai_feature_packages"; then
    echo "Creating ai_feature_packages table for local quota mock"
    apply_ai_feature_packages_table
  fi
  if ! table_exists "user_ai_quotas"; then
    echo "Creating user_ai_quotas table for local quota mock"
    apply_user_ai_quotas_table
  fi
  if ! table_exists "quota_ledger"; then
    echo "Creating quota_ledger table for local quota mock"
    apply_quota_ledger_table
  fi

  if ! column_exists "user_ai_quotas" "plan_balance"; then
    echo "Applying user_ai_quotas plan_balance patch"
    apply_user_ai_quotas_plan_balance_column
  fi
  if ! column_exists "user_ai_quotas" "plan_period_start"; then
    echo "Applying user_ai_quotas plan_period_start patch"
    apply_user_ai_quotas_plan_period_start_column
  fi
  if ! column_exists "user_ai_quotas" "plan_period_end"; then
    echo "Applying user_ai_quotas plan_period_end patch"
    apply_user_ai_quotas_plan_period_end_column
  fi

  if ! column_exists "quota_ledger" "idempotency_key"; then
    echo "Applying quota_ledger idempotency_key patch"
    apply_quota_ledger_idempotency_key_column
  fi
  if ! column_exists "quota_ledger" "subscription_id"; then
    echo "Applying quota_ledger subscription_id patch"
    apply_quota_ledger_subscription_id_column
  fi
  if ! column_exists "quota_ledger" "invoice_id"; then
    echo "Applying quota_ledger invoice_id patch"
    apply_quota_ledger_invoice_id_column
  fi
  if ! column_exists "quota_ledger" "plan_balance_after"; then
    echo "Applying quota_ledger plan_balance_after patch"
    apply_quota_ledger_plan_balance_after_column
  fi
  if ! column_exists "quota_ledger" "addon_balance_after"; then
    echo "Applying quota_ledger addon_balance_after patch"
    apply_quota_ledger_addon_balance_after_column
  fi
  if ! index_exists "quota_ledger" "uk_quota_ledger_feature_type_idempotency"; then
    echo "Applying quota_ledger idempotency index patch"
    apply_quota_ledger_idempotency_index
  fi

  echo "Ensuring V2 billing catalog and account mock tables"
  apply_sql_file "${SCRIPT_DIR}/sql/056_subscription_catalog.sql"
  apply_sql_file "${SCRIPT_DIR}/sql/057_user_subscriptions_and_webhook_events.sql"
  apply_sql_file "${SCRIPT_DIR}/sql/059_user_addon_grants.sql"
  apply_sql_file "${SCRIPT_DIR}/sql/060_quota_ledger_allocations.sql"
  apply_sql_file "${SCRIPT_DIR}/sql/064_payment_resume_context.sql"

  if table_exists "user_subscriptions"; then
    if ! column_exists "user_subscriptions" "pending_upgrade_order_no"; then
      echo "Applying user_subscriptions pending_upgrade_order_no patch"
      apply_user_subscriptions_pending_upgrade_order_no_column
    fi
    if ! column_exists "user_subscriptions" "pending_upgrade_expires_at"; then
      echo "Applying user_subscriptions pending_upgrade_expires_at patch"
      apply_user_subscriptions_pending_upgrade_expires_at_column
    fi
    if ! index_exists "user_subscriptions" "idx_user_subscription_pending_upgrade"; then
      echo "Applying user_subscriptions pending upgrade index patch"
      apply_user_subscriptions_pending_upgrade_index
    fi
  fi

  if ! table_exists "recharge_orders"; then
    echo "Creating recharge_orders table for local billing mock"
    apply_recharge_orders_table
  else
    if ! column_exists "recharge_orders" "order_type"; then
      echo "Applying recharge_orders order_type patch"
      apply_recharge_orders_order_type_column
    fi
    if ! column_exists "recharge_orders" "plan_code"; then
      echo "Applying recharge_orders plan_code patch"
      apply_recharge_orders_plan_code_column
    fi
    if ! column_exists "recharge_orders" "addon_code"; then
      echo "Applying recharge_orders addon_code patch"
      apply_recharge_orders_addon_code_column
    fi
    if ! column_exists "recharge_orders" "target_plan_code"; then
      echo "Applying recharge_orders target_plan_code patch"
      apply_recharge_orders_target_plan_code_column
    fi
    if ! column_exists "recharge_orders" "quoted_amount_cents"; then
      echo "Applying recharge_orders quoted_amount_cents patch"
      apply_recharge_orders_quoted_amount_cents_column
    fi
    if ! column_exists "recharge_orders" "upgrade_charge_type"; then
      echo "Applying recharge_orders upgrade_charge_type patch"
      apply_recharge_orders_upgrade_charge_type_column
    fi
    if ! column_exists "recharge_orders" "stripe_invoice_id"; then
      echo "Applying recharge_orders stripe_invoice_id patch"
      apply_recharge_orders_stripe_invoice_id_column
    fi
    if ! column_exists "recharge_orders" "stripe_subscription_id"; then
      echo "Applying recharge_orders stripe_subscription_id patch"
      apply_recharge_orders_stripe_subscription_id_column
    fi
    if ! column_exists "recharge_orders" "upgrade_effective_at"; then
      echo "Applying recharge_orders upgrade_effective_at patch"
      apply_recharge_orders_upgrade_effective_at_column
    fi
    if ! column_exists "recharge_orders" "switch_attempts"; then
      echo "Applying recharge_orders switch_attempts patch"
      apply_recharge_orders_switch_attempts_column
    fi
    if ! column_exists "recharge_orders" "biz_context"; then
      echo "Applying recharge_orders biz_context patch"
      apply_recharge_orders_biz_context_column
    fi
    if ! index_exists "recharge_orders" "uk_recharge_stripe_invoice"; then
      echo "Applying recharge_orders stripe_invoice index patch"
      apply_recharge_orders_stripe_invoice_index
    fi
    if ! index_exists "recharge_orders" "idx_recharge_subscription"; then
      echo "Applying recharge_orders subscription index patch"
      apply_recharge_orders_subscription_index
    fi
    if ! index_exists "recharge_orders" "idx_recharge_order_type_status"; then
      echo "Applying recharge_orders order_type status index patch"
      apply_recharge_orders_order_type_status_index
    fi
    if ! index_exists "recharge_orders" "idx_recharge_upgrade_user_status"; then
      echo "Applying recharge_orders upgrade user status index patch"
      apply_recharge_orders_upgrade_user_status_index
    fi
  fi

  seed_mock_quota_features

  if table_exists "verla_attachments"; then
    if ! column_exists "verla_attachments" "attachment_origin"; then
      echo "Applying verla_attachments attachment_origin patch"
      apply_verla_attachments_attachment_origin_column
    fi
  fi

  # Local mock DBs are long-lived; patch individual commercialization columns
  # instead of replaying full ALTER scripts so repeated starts stay idempotent.
  if table_exists "verla_sessions"; then
    if ! column_exists "verla_sessions" "quota_ledger_id"; then
      echo "Applying verla_sessions quota_ledger_id patch"
      apply_verla_sessions_quota_ledger_id_column
    fi
    if ! column_exists "verla_sessions" "quota_amount"; then
      echo "Applying verla_sessions quota_amount patch"
      apply_verla_sessions_quota_amount_column
    fi
    if ! index_exists "verla_sessions" "idx_quota_ledger_id"; then
      echo "Applying verla_sessions quota_ledger_id index patch"
      apply_verla_sessions_quota_ledger_id_index
    fi
  else
    echo "WARN: verla_sessions table does not exist; skip Verla quota patch. Apply sql/026_V2_verla_schema.sql first." >&2
  fi

  if table_exists "humanizer_tasks"; then
    if ! column_exists "humanizer_tasks" "quota_ledger_id"; then
      echo "Applying humanizer_tasks quota_ledger_id patch"
      apply_humanizer_tasks_quota_ledger_id_column
    fi
    if ! column_exists "humanizer_tasks" "total_words"; then
      echo "Applying humanizer_tasks total_words patch"
      apply_humanizer_tasks_total_words_column
    fi
    if ! column_exists "humanizer_tasks" "consumed_words"; then
      echo "Applying humanizer_tasks consumed_words patch"
      apply_humanizer_tasks_consumed_words_column
    fi
  fi

  if table_exists "verla_conversations"; then
    if ! table_exists "verla_editor_contents" || ! table_exists "verla_editor_content_versions"; then
      echo "Applying Verla editor content storage patch"
      apply_sql_file "${SCRIPT_DIR}/sql/043_conversation_based_editor_storage.sql"
    fi
    if ! table_exists "verla_editor_previews"; then
      echo "Applying Verla editor previews patch"
      apply_sql_file "${SCRIPT_DIR}/sql/051_editor_previews.sql"
    fi
  else
    echo "WARN: verla_conversations table does not exist; skip Verla editor storage patches. Apply sql/026_V2_verla_schema.sql first." >&2
  fi

  if ! table_exists "verla_workforce_tasks" || ! table_exists "verla_workforce_task_outputs"; then
    echo "Applying Verla workforce task tables patch"
    apply_sql_file "${SCRIPT_DIR}/sql/047_verla_workforce_tasks.sql"
  fi

  if table_exists "verla_workforce_tasks"; then
    if [[ "$(column_value "verla_workforce_tasks" "task_agent" "DATA_TYPE")" != "text" ]] \
      || [[ "$(column_value "verla_workforce_tasks" "task_name" "CHARACTER_MAXIMUM_LENGTH")" != "512" ]]; then
      echo "Applying Verla workforce task_agent width patch"
      apply_sql_file "${SCRIPT_DIR}/sql/049_verla_workforce_tasks_widen_task_agent.sql"
    fi
    if ! column_exists "verla_workforce_tasks" "compose_current_round"; then
      echo "Applying Verla workforce compose_current_round patch"
      apply_verla_workforce_tasks_compose_current_round_column
    fi
    if ! column_exists "verla_workforce_tasks" "compose_total_rounds"; then
      echo "Applying Verla workforce compose_total_rounds patch"
      apply_verla_workforce_tasks_compose_total_rounds_column
    fi
    if [[ "$(column_value "verla_workforce_tasks" "node_kind" "COLUMN_COMMENT")" != *compose* ]]; then
      echo "Applying Verla workforce node_kind comment patch"
      apply_verla_workforce_tasks_node_kind_comment
    fi
  fi

  if table_exists "verla_tool_calls" && ! column_exists "verla_tool_calls" "node_id"; then
    echo "Applying verla_tool_calls node_id patch"
    apply_sql_file "${SCRIPT_DIR}/sql/048_verla_tool_calls_add_node_id.sql"
  fi

  if ! table_exists "mq_outbox"; then
    echo "WARN: mq_outbox table does not exist; skip Spring Boot mock schema patch." >&2
    return
  fi

  if ! column_exists "mq_outbox" "correlation_id"; then
    echo "Applying mq_outbox Verla columns patch"
    apply_mq_outbox_verla_columns
  fi

  if [[ "$(column_value "mq_outbox" "task_id" "IS_NULLABLE")" != "YES" ]]; then
    echo "Applying mq_outbox task_id nullable patch"
    apply_mq_outbox_task_id_nullable
  fi

  if ! column_exists "mq_outbox" "worker_id"; then
    echo "Applying mq_outbox claim columns patch"
    apply_mq_outbox_claim_columns
  fi
}

if [[ "${START_DEPS}" == "true" ]]; then
  if [[ ${#compose_cmd[@]} -eq 0 ]]; then
    echo "ERROR: docker compose is required to start local MySQL/RabbitMQ." >&2
    exit 1
  fi
  if [[ ! -f "${COMPOSE_FILE}" ]]; then
    echo "ERROR: compose file not found: ${COMPOSE_FILE}" >&2
    exit 1
  fi

  echo "Starting local dependencies: mysql rabbitmq"
  (cd "${DEPS_DIR}" && "${compose_cmd[@]}" -f "${COMPOSE_FILE}" up -d mysql rabbitmq)
fi

ensure_mock_rabbit_topology
ensure_mock_db_schema

if [[ "${BUILD_FIRST}" == "true" ]]; then
  echo "Installing Spring Boot modules locally with tests skipped"
  (cd "${SCRIPT_DIR}" && mvn install -DskipTests)
fi

run_args=(
  "--server.port=${PORT}"
  "--spring.datasource.url=jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
  "--spring.datasource.username=${DB_USERNAME}"
  "--spring.datasource.password=${DB_PASSWORD}"
  "--spring.rabbitmq.host=${RABBITMQ_HOST}"
  "--spring.rabbitmq.port=${RABBITMQ_PORT}"
  "--spring.rabbitmq.username=${RABBITMQ_USERNAME}"
  "--spring.rabbitmq.password=${RABBITMQ_PASSWORD}"
  "--spring.rabbitmq.virtual-host=${RABBITMQ_VHOST}"
  "--verla.mq.dead-letter-enabled=false"
  "--verla.mq.single-active-consumer-enabled=false"
  "--verla.mq.mock.enabled=true"
  "--verla.mq.mock.delay-base-ms=${VERLA_MOCK_DELAY_MS}"
  "--billing.portal.mock-url=${BILLING_PORTAL_MOCK_URL}"
  "--billing.checkout.mock-enabled=${BILLING_CHECKOUT_MOCK_ENABLED}"
  "--payment.checkout.mock-enabled=${PAYMENT_CHECKOUT_MOCK_ENABLED}"
)

echo "Starting Spring Boot MockPy backend on http://localhost:${PORT}"
echo "Profile: ${SPRING_PROFILE}; DB: ${DB_HOST}:${DB_PORT}/${DB_NAME}; RabbitMQ: ${RABBITMQ_HOST}:${RABBITMQ_PORT}"
echo "Use BUILD_FIRST=false, START_DEPS=false, or PATCH_MOCK_DB=false to skip those steps when needed."

cd "${AGENT_START_DIR}"
exec mvn spring-boot:run \
  -Dspring-boot.run.profiles="${SPRING_PROFILE}" \
  -Dspring-boot.run.arguments="${run_args[*]}"
