-- Persist the first-turn feature selection so a reload can still tell
-- "not generated yet" from "still generating".
-- NULL = legacy note (all features); empty string = note body only.
-- One-shot migration. Apply with --default-character-set=utf8mb4.

USE studyagent;

ALTER TABLE demo_note_conversation
    ADD COLUMN features VARCHAR(128) NULL AFTER infographic_style;
