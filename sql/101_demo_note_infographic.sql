-- AI Note 知识图：与首轮 extras 同批生成，落 OSS URL
ALTER TABLE demo_note_conversation
    ADD COLUMN infographic_url VARCHAR(1024) NULL AFTER quiz_json;
