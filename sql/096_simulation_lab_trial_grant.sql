-- Simulation Lab one-time trial regrant.
-- Existing users receive a lazy grant on their first entitlement check after
-- this release; successes before granted_at no longer consume the new trial.
CREATE TABLE IF NOT EXISTS simulation_lab_trial_grants (
    clerk_user_id VARCHAR(64) NOT NULL,
    granted_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    used_conversation_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (clerk_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
