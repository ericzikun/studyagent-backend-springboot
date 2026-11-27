package com.studyagent.infra.entity.demo.note;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_note_conversation */
@Data
@TableName("demo_note_conversation")
public class DemoNoteConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("clerk_user_id")
    private String clerkUserId;
    @TableField("verla_conversation_id")
    private Long verlaConversationId;
    private String title;
    @TableField("source_type")
    private String sourceType;
    @TableField("attachment_object_id")
    private String attachmentObjectId;
    /** 上传附件的原始文件名（title 会被 AI 起的会话名覆盖，所以另存一份）。 */
    @TableField("attachment_filename")
    private String attachmentFilename;
    @TableField("input_text")
    private String inputText;
    @TableField("course_name")
    private String courseName;
    @TableField("learning_goal")
    private String learningGoal;
    /** 表单：笔记输出语言码。 */
    @TableField("output_language")
    private String outputLanguage;
    @TableField("note_md")
    private String noteMd;
    @TableField("note_version")
    private Long noteVersion;
    /**
     * 待用户接受/忽略的章节级改动提案（JSON）；NULL = 无待审提案。
     * <p>必须用 {@code ALWAYS}：MyBatis-Plus 默认 {@code updateById} 跳过 null 字段，
     * 提交/忽略提案时置 null 不会落库，旧提案会一直留在行里（重放补刀后前端又进审阅态）。
     */
    @TableField(value = "pending_proposal_json", updateStrategy = FieldStrategy.ALWAYS)
    private String pendingProposalJson;
    /** 重新生成时必须能写回 NULL：默认 updateById 跳过 null，旧导图会一直残留。 */
    @TableField(value = "mindmap_json", updateStrategy = FieldStrategy.ALWAYS)
    private String mindmapJson;
    @TableField(value = "blog_md", updateStrategy = FieldStrategy.ALWAYS)
    private String blogMd;
    /** 四选一测验 JSON；重新生成时必须能写回 NULL。 */
    @TableField(value = "quiz_json", updateStrategy = FieldStrategy.ALWAYS)
    private String quizJson;
    /** 知识图 OSS URL；重新生成时必须能写回 NULL。 */
    @TableField(value = "infographic_url", updateStrategy = FieldStrategy.ALWAYS)
    private String infographicUrl;
    /** 知识图状态：NONE / GENERATING / READY / FAILED / QUOTA_EXHAUSTED。 */
    @TableField(value = "infographic_status", updateStrategy = FieldStrategy.ALWAYS)
    private String infographicStatus;
    /** 知识图风格：classic / game_hud / hand_comic / mii_3d。 */
    @TableField(value = "infographic_style", updateStrategy = FieldStrategy.ALWAYS)
    private String infographicStyle;
    /** 首轮勾选的扩展功能（逗号分隔）；NULL = 旧会话（全部），空串 = 只生成笔记。 */
    @TableField(value = "features", updateStrategy = FieldStrategy.ALWAYS)
    private String features;
    /** PROCESSING / READY / FAILED；重新生成时必须能写回 NULL。 */
    @TableField(value = "blog_audio_status", updateStrategy = FieldStrategy.ALWAYS)
    private String blogAudioStatus;
    @TableField(value = "blog_audio_json", updateStrategy = FieldStrategy.ALWAYS)
    private String blogAudioJson;
    @TableField(value = "blog_audio_source_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String blogAudioSourceHash;
    private String status;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
