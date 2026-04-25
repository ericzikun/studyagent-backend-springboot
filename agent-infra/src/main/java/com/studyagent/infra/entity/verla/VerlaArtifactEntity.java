package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_artifacts 表实体
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.6。\
 */
@Data
@Accessors(chain = true)
@TableName("verla_artifacts")
public class VerlaArtifactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private String kind;
    private String mime;
    private String bodyOrRef;
    private Integer version;
    private LocalDateTime updatedAt;
}
