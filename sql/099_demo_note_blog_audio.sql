ALTER TABLE demo_note_conversation
  ADD COLUMN blog_audio_status VARCHAR(16) NULL AFTER blog_md,
  ADD COLUMN blog_audio_json LONGTEXT NULL AFTER blog_audio_status,
  ADD COLUMN blog_audio_source_hash VARCHAR(64) NULL AFTER blog_audio_json;
