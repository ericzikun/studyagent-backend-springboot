ALTER TABLE verla_attachments
    ADD COLUMN deleted_at DATETIME NULL AFTER updated_at,
    ADD INDEX idx_verla_attachment_conv_origin_deleted (conversation_id, attachment_origin, deleted_at, created_at);

CREATE TABLE verla_followup_edit_usages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    assignment_session_id BIGINT NOT NULL,
    clerk_user_id VARCHAR(255) NOT NULL,
    user_message_id BIGINT NOT NULL,
    assignment_chat_session_id BIGINT NULL,
    state VARCHAR(16) NOT NULL COMMENT 'RESERVED/COMPLETED/RELEASED',
    release_reason VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_followup_usage_user_message (user_message_id),
    INDEX idx_followup_usage_scope_state (assignment_session_id, state)
);
