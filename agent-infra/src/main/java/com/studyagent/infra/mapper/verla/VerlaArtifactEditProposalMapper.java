package com.studyagent.infra.mapper.verla;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.infra.entity.verla.VerlaArtifactEditProposalEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VerlaArtifactEditProposalMapper extends BaseMapper<VerlaArtifactEditProposalEntity> {

    @Select("SELECT * FROM verla_artifact_edit_proposals WHERE proposal_id = #{pid} LIMIT 1")
    VerlaArtifactEditProposalEntity selectByProposalId(@Param("pid") String proposalId);

    @Select("SELECT * FROM verla_artifact_edit_proposals "
            + "WHERE turn_id = #{turnId} "
            + "ORDER BY updated_at DESC, id DESC LIMIT 1")
    VerlaArtifactEditProposalEntity selectLatestByTurnId(@Param("turnId") Long turnId);

    /** 当前 conversation 下尚未终结（GENERATING / REVIEWING）的提案，供快照恢复，取最新一条。 */
    @Select("SELECT * FROM verla_artifact_edit_proposals "
            + "WHERE conversation_id = #{cid} AND state IN ('GENERATING','REVIEWING') "
            + "ORDER BY created_at DESC, id DESC")
    List<VerlaArtifactEditProposalEntity> selectActiveByConversation(@Param("cid") Long conversationId);

    @Update("UPDATE verla_artifact_edit_proposals SET state = #{newState}, updated_at = #{ts}, "
            + "resolved_at = CASE WHEN #{newState} IN ('COMMITTED','FAILED','CANCELLED','SUPERSEDED') "
            + "THEN #{ts} ELSE resolved_at END "
            + "WHERE conversation_id = #{cid} AND state IN ('GENERATING','REVIEWING') "
            + "AND proposal_id <> #{keepProposalId}")
    int supersedeActiveExcept(@Param("cid") Long conversationId,
                              @Param("keepProposalId") String keepProposalId,
                              @Param("newState") String newState,
                              @Param("ts") LocalDateTime ts);
}
