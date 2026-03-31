package com.studyagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_announcement_reads")
public class UserAnnouncementReadEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("clerk_user_id")
    private String clerkUserId;

    @TableField("announcement_public_id")
    private String announcementPublicId;

    @TableField("read_at")
    private LocalDateTime readAt;
}
