-- 附件 checksum 级解析缓存查询：按 (checksum_sha256, user_id) 命中已解析记录
ALTER TABLE verla_attachments
    ADD INDEX idx_verla_attachment_checksum_user (checksum_sha256, user_id);
