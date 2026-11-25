-- AI Note 知识图风格：classic / game_hud / hand_comic / mii_3d
ALTER TABLE demo_note_conversation
    ADD COLUMN infographic_style VARCHAR(32) NULL DEFAULT 'classic' AFTER infographic_url;
