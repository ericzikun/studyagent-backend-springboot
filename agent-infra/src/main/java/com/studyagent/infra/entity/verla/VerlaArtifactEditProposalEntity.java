package com.studyagent.infra.entity.verla;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * verla_artifact_edit_proposals 表实体（Chat With Assignment / write）。
 */
@Data
@Accessors(chain = true)
@TableName("verla_artifact_edit_proposals")
public class VerlaArtifactEditProposalEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String proposalId;
    private Long conversationId;
    private Long turnId;
    private String state;
    private String targetsJson;
    private String changesJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime updatedAt;
}
