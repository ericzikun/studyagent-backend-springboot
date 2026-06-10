-- 053_verla_artifact_edit_proposals.sql
-- Chat With Assignment / write 模式：Edit Proposal 暂存表（chat_with_assignment 协议 §9.3）。
-- 承载"未确认 diff / 锁定状态"的服务端真相，支撑 commit 与刷新恢复。
-- review 目标的 hunks 落 changes_json，commit 时才提升为 verla_artifacts 新版本；
-- overwrite 目标的正文走现有 *_ARTIFACT_UPDATED 路径直接覆盖，这里只记 targets 摘要。

CREATE TABLE IF NOT EXISTS verla_artifact_edit_proposals (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    proposal_id       VARCHAR(96)  NOT NULL               COMMENT '业务唯一 ID（ep_{conversationId}_{turnId}）',
    conversation_id   BIGINT       NOT NULL,
    turn_id           BIGINT       DEFAULT NULL            COMMENT '产生该提案的 chat 轮',
    state             VARCHAR(16)  NOT NULL DEFAULT 'GENERATING'
                          COMMENT 'GENERATING / REVIEWING / COMMITTED / FAILED / CANCELLED / SUPERSEDED',
    targets_json      JSON         DEFAULT NULL            COMMENT '全部 target（artifactUid/kind/title/editMode/baseVersionNo/versionNo）',
    changes_json      JSON         DEFAULT NULL            COMMENT 'review 目标的 EditChangeHunk[]（按 artifactUid 分组；overwrite 无）',
    error_message     VARCHAR(1024) DEFAULT NULL,
    created_at        DATETIME     NOT NULL,
    resolved_at       DATETIME     DEFAULT NULL            COMMENT 'COMMITTED / FAILED / CANCELLED / SUPERSEDED 时间',
    updated_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_proposal_id (proposal_id),
    KEY idx_conv (conversation_id),
    KEY idx_conv_state (conversation_id, state)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT 'Chat With Assignment write 模式 Edit Proposal 暂存（待确认 diff / 锁定状态）';
