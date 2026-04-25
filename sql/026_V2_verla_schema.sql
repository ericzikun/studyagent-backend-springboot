-- ========================================
-- 迁移脚本 026_V2: Verla 三层会话 + 保序基础设施
-- 创建日期: 2026-04-25
-- 说明:
--   对应文档 docs/verla-Java侧MVP技术方案.md §4.3 / §4.4 / §4.5
--   建立 conversation -> turn -> session -> step 三层模型
--   建立 verla_event_inbox / verla_event_cursor 保序基础设施
--   建立 ShedLock 表（多 Java 实例分布式锁）
-- ========================================

USE studyagent;

-- ----------------------------------------
-- 1) 用户面向的对话容器（左栏一个 Tab）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_conversations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)  NOT NULL                        COMMENT '用户ID（Clerk userId 或内部用户ID）',
    title           VARCHAR(255) DEFAULT NULL                    COMMENT '对话标题，前端可重命名',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active'       COMMENT 'active / archived / deleted',
    primary_intent  VARCHAR(64)  DEFAULT NULL                    COMMENT '该 conv 沉淀的主意图，下一轮可跳 plan',
    workspace_json  JSON         DEFAULT NULL                    COMMENT '全局偏好/工具开关',
    turn_count      INT          NOT NULL DEFAULT 0              COMMENT '已发生的 turn 总数',
    last_turn_id    BIGINT       DEFAULT NULL                    COMMENT '最后一个 turn 的 id',
    last_message_at DATETIME     DEFAULT NULL                    COMMENT '最后一条消息时间（用于排序）',
    version         BIGINT       NOT NULL DEFAULT 1              COMMENT 'Redis 缓存 key 版本号',
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_status_lm (user_id, status, last_message_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 用户对话容器（左栏 Tab）';

-- ----------------------------------------
-- 2) 一次 query → 系统响应完成（一轮交互）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_turns (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id      BIGINT       NOT NULL                  COMMENT '所属 conversation',
    user_message_id      BIGINT       DEFAULT NULL              COMMENT '触发本 turn 的 user message',
    status               VARCHAR(24)  NOT NULL                  COMMENT 'CREATED/PLANNING/AWAITING_CLARIFY/DISPATCHING/RUNNING_AGENT/COMPLETED/FAILED/CANCELLING/CANCELLED',
    resolved_intent      VARCHAR(64)  DEFAULT NULL              COMMENT 'plan 完成后落入的意图',
    resolved_slots_json  JSON         DEFAULT NULL              COMMENT 'plan 提取的槽位',
    active_session_id    BIGINT       DEFAULT NULL              COMMENT '当前活跃 session',
    plan_session_id      BIGINT       DEFAULT NULL              COMMENT '所属 plan session',
    agent_session_id     BIGINT       DEFAULT NULL              COMMENT '所属 agent session',
    total_steps          INT          DEFAULT NULL              COMMENT '当前 agent 的总 step 数',
    completed_steps      INT          NOT NULL DEFAULT 0        COMMENT '已完成的 step 数',
    last_progress_at     DATETIME     DEFAULT NULL              COMMENT '最近一次进度推进时间（看门狗用）',
    started_at           DATETIME     DEFAULT NULL,
    ended_at             DATETIME     DEFAULT NULL,
    error_json           JSON         DEFAULT NULL              COMMENT '终态为 FAILED 时的错误详情',
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id, created_at),
    KEY idx_watchdog (status, last_progress_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 单轮交互（一次 query 到响应）';

-- ----------------------------------------
-- 3) 一次 Py 调用的会话（plan/agent/materials）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_sessions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT       NOT NULL                   COMMENT '所属 conversation',
    turn_id             BIGINT       NOT NULL                   COMMENT '所属 turn',
    kind                VARCHAR(16)  NOT NULL                   COMMENT 'PLAN / AGENT / MATERIALS',
    feature_code        VARCHAR(64)  DEFAULT NULL               COMMENT '功能码：assignment / flashcards / outline ...',
    status              VARCHAR(16)  NOT NULL                   COMMENT 'CREATED/DISPATCHING/RUNNING/SUCCEEDED/FAILED/CANCELLING/CANCELLED',
    correlation_id      VARCHAR(160) NOT NULL                   COMMENT 'conv:{c}:turn:{t}:sess:{s}',
    context_ref_json    JSON         DEFAULT NULL               COMMENT '给 Py 的 contextRef 指针',
    result_json         JSON         DEFAULT NULL               COMMENT '终稿结构化结果（plan 给意图，agent 给摘要）',
    error_json          JSON         DEFAULT NULL,
    expected_seq        BIGINT       NOT NULL DEFAULT 1         COMMENT '冗余自 cursor，便于排障',
    last_event_seq      BIGINT       NOT NULL DEFAULT 0         COMMENT '已处理到的最大 eventSeq',
    last_progress_at    DATETIME     DEFAULT NULL,
    started_at          DATETIME     DEFAULT NULL,
    ended_at            DATETIME     DEFAULT NULL,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_correlation (correlation_id),
    KEY idx_turn (turn_id, kind),
    KEY idx_conv (conversation_id, created_at),
    KEY idx_watchdog (status, last_progress_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 单次 Py 调用会话';

-- ----------------------------------------
-- 4) session 内的 sub-step（多 sub-agent 时使用）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_session_steps (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    session_id      BIGINT       NOT NULL,
    step_id         VARCHAR(64)  NOT NULL                       COMMENT 'Py 侧分配的 stepId（全局唯一）',
    step_kind       VARCHAR(32)  NOT NULL                       COMMENT 'outline / fillin / quiz_gen ...',
    step_order      INT          NOT NULL                       COMMENT '步骤序号（1..N）',
    sub_agent       VARCHAR(64)  DEFAULT NULL                   COMMENT '执行的子 agent 名',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending'     COMMENT 'pending / running / succeeded / failed',
    last_step_seq   INT          DEFAULT NULL                   COMMENT 'step 内最大 streamSeq（仅排障）',
    summary_json    JSON         DEFAULT NULL,
    started_at      DATETIME     DEFAULT NULL,
    ended_at        DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_step_id (step_id),
    KEY idx_session (session_id, step_order)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla session 内的子步骤';

-- ----------------------------------------
-- 5) 用户/助手消息
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_messages (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT       NOT NULL,
    turn_id             BIGINT       NOT NULL,
    role                VARCHAR(16)  NOT NULL                   COMMENT 'user / assistant / system',
    source_session_id   BIGINT       DEFAULT NULL               COMMENT 'assistant 消息的来源 session（plan/agent）',
    text_content        TEXT         DEFAULT NULL,
    blocks_json         JSON         DEFAULT NULL               COMMENT '卡片 / block 数组（见 §22.2）',
    attachments_json    JSON         DEFAULT NULL,
    meta_json           JSON         DEFAULT NULL,
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_conv (conversation_id, created_at),
    KEY idx_turn (turn_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 对话消息';

-- ----------------------------------------
-- 6) artifact（卡片/材料终稿）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_artifacts (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    turn_id         BIGINT       NOT NULL,
    session_id      BIGINT       NOT NULL,
    kind            VARCHAR(32)  NOT NULL                       COMMENT 'assignment_card / flashcards / outline ...',
    mime            VARCHAR(64)  DEFAULT NULL,
    body_or_ref     LONGTEXT     DEFAULT NULL                   COMMENT '正文（小）或 OSS 引用（大）',
    version         INT          NOT NULL DEFAULT 1             COMMENT '增量更新版本',
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session (session_id),
    KEY idx_conv_kind (conversation_id, kind, version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 卡片/材料终稿';

-- ----------------------------------------
-- 7) 卡片回填记录（block actions）
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_block_responses (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    message_id        BIGINT       NOT NULL                     COMMENT '回填的目标 assistant message',
    block_id          VARCHAR(64)  NOT NULL                     COMMENT 'message 内的 block id',
    action_id         VARCHAR(64)  NOT NULL                     COMMENT 'submit_answer / reveal_answer ...',
    client_action_id  VARCHAR(64)  NOT NULL                     COMMENT '前端幂等 id',
    values_json       JSON         DEFAULT NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_action (client_action_id),
    KEY idx_turn (turn_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla block 回填动作';

-- ----------------------------------------
-- 8) 入站事件存档 + 保序待处理池
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_event_inbox (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    message_id        VARCHAR(64)  NOT NULL                     COMMENT '= eventId，全局唯一，去重用',
    correlation_id    VARCHAR(160) NOT NULL,
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    session_id        BIGINT       NOT NULL                     COMMENT '保序键',
    event_seq         BIGINT       NOT NULL                     COMMENT 'session 内单调',
    event_type        VARCHAR(64)  NOT NULL,
    step_id           VARCHAR(64)  DEFAULT NULL,
    step_seq          INT          DEFAULT NULL,
    payload_json      JSON         NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'READY'     COMMENT 'READY / PROCESSED / SKIPPED / FAILED',
    error_message     VARCHAR(1024) DEFAULT NULL,
    received_at       DATETIME     NOT NULL,
    processed_at      DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_id (message_id),
    UNIQUE KEY uk_session_seq (session_id, event_seq),
    KEY idx_session_status (session_id, status, event_seq),
    KEY idx_conv (conversation_id, received_at),
    KEY idx_correlation (correlation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 入站事件存档 + 保序待处理池';

-- ----------------------------------------
-- 9) 每个 session 的处理光标
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_event_cursor (
    session_id            BIGINT NOT NULL,
    conversation_id       BIGINT NOT NULL                       COMMENT '冗余便于按 conv 聚合',
    turn_id               BIGINT NOT NULL,
    next_expected_seq     BIGINT NOT NULL DEFAULT 1             COMMENT '下一条期望的 eventSeq',
    last_processed_seq    BIGINT NOT NULL DEFAULT 0             COMMENT '已成功处理的最大 eventSeq',
    updated_at            DATETIME NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_conv (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla session 级处理光标';

-- ----------------------------------------
-- 10) ShedLock：多 Java 实例分布式调度锁
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)   NOT NULL,
    lock_until  TIMESTAMP(3)  NOT NULL,
    locked_at   TIMESTAMP(3)  NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT '分布式调度锁（ShedLock）';
