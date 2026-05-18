package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_artifacts 表实体（V2 扩展）。
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §4.6 与
 * docs/V2/5.1 Java后端 + 数据库 V2 升级技术方案.md §3。
 */
@Data
@Accessors(chain = true)
@TableName("verla_artifacts")
public class VerlaArtifactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String artifactUid;
    private Long conversationId;
    private Long turnId;
    private Long sessionId;
    private Long sourceMessageId;
    private String sourceObjectId;
    private String kind;
    private String mime;
    private String summary;
    private String contentRef;
    private String bodyOrRef;
    private String status;
    private Long sizeBytes;
    private Integer version;
    private String metaJson;
    private LocalDateTime updatedAt;
}
