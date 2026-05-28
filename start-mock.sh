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

apply_verla_attachments_attachment_origin_column() {
  run_mysql <<SQL
ALTER TABLE \`${DB_NAME}\`.\`verla_attachments\`
    ADD COLUMN attachment_origin VARCHAR(32) NOT NULL DEFAULT 'USER_UPLOAD' COMMENT 'USER_UPLOAD / AGENT_OUTPUT' AFTER primary_artifact_uid;
SQL
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

  if table_exists "verla_attachments"; then
    if ! column_exists "verla_attachments" "attachment_origin"; then
      echo "Applying verla_attachments attachment_origin patch"
      apply_verla_attachments_attachment_origin_column
    fi
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
)

echo "Starting Spring Boot MockPy backend on http://localhost:${PORT}"
echo "Profile: ${SPRING_PROFILE}; DB: ${DB_HOST}:${DB_PORT}/${DB_NAME}; RabbitMQ: ${RABBITMQ_HOST}:${RABBITMQ_PORT}"
echo "Use BUILD_FIRST=false, START_DEPS=false, or PATCH_MOCK_DB=false to skip those steps when needed."

cd "${AGENT_START_DIR}"
exec mvn spring-boot:run \
  -Dspring-boot.run.profiles="${SPRING_PROFILE}" \
  -Dspring-boot.run.arguments="${run_args[*]}"
