package com.studyagent.infra.entity.demo.aitutor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** demo_ai_tutor_conversation */
@Data
@TableName("demo_ai_tutor_conversation")
public class DemoAiTutorConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("clerk_user_id")
    private String clerkUserId;
    private String title;
    @TableField("initial_query")
    private String initialQuery;
    @TableField("paper_meta")
    private String paperMeta;
    private String status;
    @TableField("base_version")
    private Long baseVersion;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
