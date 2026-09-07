package com.studyagent.infra.entity.demo.aitutor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_ai_tutor_doc_version */
@Data
@TableName("demo_ai_tutor_doc_version")
public class DemoAiTutorDocVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("document_id")
    private Long documentId;
    @TableField("version_no")
    private Long versionNo;
    private String source;
    @TableField("content_md")
    private String contentMd;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
