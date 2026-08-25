package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公开邮箱留资表映射；字段严格保持 MVP 最小集合。
 */
@Data
@TableName("email_leads")
public class PublicEmailLeadEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("email_normalized")
    private String emailNormalized;

    @TableField("source_path")
    private String sourcePath;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
