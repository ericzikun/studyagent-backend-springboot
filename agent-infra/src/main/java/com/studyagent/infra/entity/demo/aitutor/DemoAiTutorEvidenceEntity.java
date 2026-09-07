package com.studyagent.infra.entity.demo.aitutor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_ai_tutor_evidence */
@Data
@TableName("demo_ai_tutor_evidence")
public class DemoAiTutorEvidenceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("conversation_id")
    private Long conversationId;
    @TableField("source_type")
    private String sourceType;
    private String title;
    private String url;
    private String snippet;
    @TableField("meta_json")
    private String metaJson;
    @TableField("seq_no")
    private Long seqNo;
    private Boolean confirmed;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
