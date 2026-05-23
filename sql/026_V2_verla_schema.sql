-- ========================================
-- 迁移脚本 026_V2: Verla 三层会话 + 保序基础设施 + V2 上下文/Tool Trace 扩展
-- 创建日期: 2026-04-25  (V1)
-- 更新日期: 2026-05-01  (V2: artifact 字段补齐 + tool_calls/attachments/clarify_forms/clarify_responses 4 张新表)
-- 说明:
--   对应文档:
--     - docs/verla-Java侧MVP技术方案.md §4.3 / §4.4 / §4.5  (V1)
--     - docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3 (V2)
--   与 docker-aliyun-20260125/migrations/037_V2_verla_schema.sql 内容完全一致
--   建立 conversation -> turn -> session -> step 三层模型
--   建立 verla_event_inbox / verla_event_cursor 保序基础设施
--   建立 ShedLock 表（多 Java 实例分布式锁）
--   V2 增量：artifact 字段直接在 CREATE 中含 V2 字段；末尾追加 4 张新表
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
-- 6) artifact（卡片/材料终稿）—— V2 字段补齐：artifact_uid / source_* / summary / content_ref / status / size_bytes / meta_json
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_artifacts (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    artifact_uid      VARCHAR(96)  DEFAULT NULL                   COMMENT 'V2: 业务唯一 ID（artifact_*），Py/前端引用用',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    session_id        BIGINT       NOT NULL,
    source_message_id BIGINT       DEFAULT NULL                   COMMENT 'V2: 触发产物的 message',
    source_object_id  VARCHAR(64)  DEFAULT NULL                   COMMENT 'V2: 上游附件 objectId（来自 verla_attachments）',
    kind              VARCHAR(32)  NOT NULL                       COMMENT 'assignment_card / flashcards / outline / document_markdown / document_summary ...',
    mime              VARCHAR(64)  DEFAULT NULL,
    summary           VARCHAR(1024) DEFAULT NULL                  COMMENT 'V2: 短摘要（hydrate 注入上下文用）',
    content_ref       VARCHAR(255) DEFAULT NULL                   COMMENT 'V2: internal:// 或 oss:// URI；正文小且落 body_or_ref 时为空',
    body_or_ref       LONGTEXT     DEFAULT NULL                   COMMENT '正文（小，<= 32KB）；超过走 content_ref',
    status            VARCHAR(16)  NOT NULL DEFAULT 'READY'       COMMENT 'V2: PENDING / READY / FAILED',
    size_bytes        BIGINT       DEFAULT NULL                   COMMENT 'V2: 正文/对象大小',
    version           INT          NOT NULL DEFAULT 1             COMMENT '增量更新版本',
    meta_json         JSON         DEFAULT NULL                   COMMENT 'V2: schemaVersion / agent / model / tokens',
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_uid (artifact_uid),
    KEY idx_session (session_id),
    KEY idx_conv_kind (conversation_id, kind, version),
    KEY idx_source_message (source_message_id),
    KEY idx_source_object  (source_object_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Verla 卡片/材料终稿（V2: 含产物状态、来源、摘要、引用）';

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

-- ============================================================================
-- V2 新增表（2026-05-01）
--   对应文档 docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3
--   - verla_tool_calls       工具调用 trace（agent 内部 vs 用户可见）
--   - verla_attachments      用户上传的文件/图片，承载解析状态、元数据
--   - verla_clarify_forms    Agent 发起的澄清问卷（动态字段）
--   - verla_clarify_responses 用户对问卷的响应
-- ============================================================================

-- ----------------------------------------
-- 11) Tool Trace（V2）—— Agent 工具调用记录
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_tool_calls (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tool_call_id      VARCHAR(96)  NOT NULL                       COMMENT '业务唯一 ID（call_*），Py 生成',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    session_id        BIGINT       NOT NULL,
    step_id           VARCHAR(64)  DEFAULT NULL                   COMMENT 'Py 层 step UUID（与 verla_event_inbox.step_id 对齐）',
    parent_call_id    VARCHAR(96)  DEFAULT NULL                   COMMENT '嵌套调用父引用，可空',
    agent_name        VARCHAR(64)  NOT NULL                       COMMENT 'planner / homework_agent / file_summary ...',
    tool_name         VARCHAR(96)  NOT NULL                       COMMENT 'web_search / pdf_extract ...',
    status            VARCHAR(16)  NOT NULL                       COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED',
    visibility        VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL'    COMMENT 'INTERNAL（仅 trace，不入聊天历史） / USER_VISIBLE',
    tool_input_json   JSON         DEFAULT NULL                   COMMENT '入参（脱敏后）',
    tool_output_json  JSON         DEFAULT NULL                   COMMENT '出参摘要（脱敏后）',
    summary           VARCHAR(1024) DEFAULT NULL                  COMMENT '人话 1 句话总结，trace 列表展示用',
    error_code        VARCHAR(64)  DEFAULT NULL,
    error_message     VARCHAR(1024) DEFAULT NULL,
    started_at        DATETIME     DEFAULT NULL,
    finished_at       DATETIME     DEFAULT NULL,
    duration_ms       INT          DEFAULT NULL,
    meta_json         JSON         DEFAULT NULL                   COMMENT 'token / model / cost / 是否裁剪等元信息',
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_call_id (tool_call_id),
    KEY idx_session_started (session_id, started_at),
    KEY idx_turn_started    (turn_id, started_at),
    KEY idx_conv_started    (conversation_id, started_at),
    KEY idx_visibility      (conversation_id, visibility, started_at),
    KEY idx_parent          (parent_call_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'V2 Verla Agent tool 调用 trace';

-- ----------------------------------------
-- 12) Attachments（V2）—— 用户上传的文件/图片
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_attachments (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    object_id         VARCHAR(64)  NOT NULL                       COMMENT '业务唯一 ID（att_*），前端引用用',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       DEFAULT NULL                   COMMENT '可空：上传后未提交时为 NULL',
    session_id        BIGINT       DEFAULT NULL                   COMMENT '可空：上传时所处 session，预上传阶段为 NULL',
    user_id           VARCHAR(64)  NOT NULL                       COMMENT 'clerkUserId',
    filename          VARCHAR(255) NOT NULL,
    mime              VARCHAR(96)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    storage_uri       VARCHAR(512) NOT NULL                       COMMENT 'oss://... / file://...',
    oss_key           VARCHAR(512) DEFAULT NULL                   COMMENT 'OSS/local object key for V2 attachment bytes',
    checksum_sha256   VARCHAR(64)  DEFAULT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED'    COMMENT 'UPLOADED / PARSING / PARSED / FAILED',
    parse_progress    INT          DEFAULT NULL                   COMMENT '0~100，PARSING 阶段',
    parse_error       VARCHAR(1024) DEFAULT NULL,
    summary           VARCHAR(1024) DEFAULT NULL                  COMMENT '解析后的短摘要，hydrate 用',
    primary_artifact_uid VARCHAR(96) DEFAULT NULL                 COMMENT '主产物（如 markdown 全文）的 verla_artifacts.artifact_uid',
    attachment_origin VARCHAR(32)  NOT NULL DEFAULT 'USER_UPLOAD' COMMENT 'USER_UPLOAD / AGENT_OUTPUT',
    meta_json         JSON         DEFAULT NULL                   COMMENT 'page_count / image_size / ocr 等',
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_object_id (object_id),
    KEY idx_conv_created (conversation_id, created_at),
    KEY idx_turn         (turn_id),
    KEY idx_session      (session_id),
    KEY idx_user_created (user_id, created_at),
    KEY idx_status       (status, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'V2 Verla 用户上传附件 + 解析状态';

-- ----------------------------------------
-- 13) Clarify Forms（V2）—— Agent 发起的澄清问卷
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_clarify_forms (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    form_id           VARCHAR(64)  NOT NULL                       COMMENT '业务唯一 ID（form_*）',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    session_id        BIGINT       NOT NULL                       COMMENT '产出该 form 的 plan/agent session',
    message_id        BIGINT       DEFAULT NULL                   COMMENT '关联的 verla_messages.id（assistant 提问那条）',
    title             VARCHAR(255) DEFAULT NULL,
    description       VARCHAR(1024) DEFAULT NULL,
    schema_json       JSON         NOT NULL                       COMMENT '动态字段定义：[{key, label, type, options, required, ...}]',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN'        COMMENT 'OPEN / SUBMITTED / EXPIRED / CANCELLED',
    expires_at        DATETIME     DEFAULT NULL,
    submitted_at      DATETIME     DEFAULT NULL,
    submitted_response_id BIGINT   DEFAULT NULL                   COMMENT '回填 verla_clarify_responses.id',
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_form_id (form_id),
    KEY idx_conv_status (conversation_id, status, created_at),
    KEY idx_turn        (turn_id),
    KEY idx_session     (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'V2 Verla Agent 澄清问卷';

-- ----------------------------------------
-- 14) Clarify Responses（V2）—— 用户对问卷的响应
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS verla_clarify_responses (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    response_uid      VARCHAR(64)  NOT NULL                       COMMENT '业务唯一 ID（resp_*），Java 生成',
    form_id           VARCHAR(64)  NOT NULL                       COMMENT '关联 verla_clarify_forms.form_id',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       NOT NULL,
    user_id           VARCHAR(64)  NOT NULL                       COMMENT '提交者 clerkUserId',
    answers_json      JSON         NOT NULL                       COMMENT '{key: value, ...}，对齐 schema',
    submitted_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_response_uid (response_uid),
    KEY idx_form (form_id),
    KEY idx_turn (turn_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'V2 Verla 澄清问卷用户响应';
