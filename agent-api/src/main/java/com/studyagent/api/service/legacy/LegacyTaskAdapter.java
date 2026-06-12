package com.studyagent.api.service.legacy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.api.dto.verla.response.AssignmentRuntimeSnapshotVO;
import com.studyagent.api.dto.verla.response.VerlaArtifactVO;
import com.studyagent.api.dto.verla.response.VerlaConversationVO;
import com.studyagent.api.dto.verla.response.VerlaMessageVO;
import com.studyagent.api.dto.verla.support.VerlaPublicIdVoSupport;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.id.LegacyConversationIdCodec;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.TaskOutputMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.0 历史任务 -> 2.0 Verla 只读适配器。
 * <p>
 * 不迁数据、不动表结构，实时把 1.0 已完成（{@code tasks.status = 3}）的
 * {@code tasks} + {@code task_outputs} 包装成 V2 的 {@link VerlaConversationVO} /
 * {@link VerlaMessageVO} / {@link VerlaArtifactVO} 返给前端。
 * <p>
 * 字段映射语义见 docs/02-架构稳定性分析/11-1.0到2.0轻量API兼容方案.md §四。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyTaskAdapter {

    /** 数据来源标记，前端据此进入只读模式。 */
    public static final String SOURCE_LEGACY = "LEGACY_1_0";

    /** 1.0 已完成状态码。 */
    private static final int STATUS_COMPLETED = 3;
    /** 最终报告 outputType。 */
    private static final int OUTPUT_TYPE_REPORT = 1;

    private static final String DEFAULT_TITLE = "未命名作业";
    private static final String ASSISTANT_TEXT = "作业已完成（来自 1.0 历史记录）";

    private final TaskMapper taskMapper;
    private final TaskOutputMapper taskOutputMapper;
    private final ObjectMapper objectMapper;

    /** 单个 artifact body 截断阈值（字节），防止超大文本撑爆响应。 */
    @Value("${verla.legacy-compat.max-message-bytes:65536}")
    private int maxMessageBytes;

    // ====================================================================
    // 列表
    // ====================================================================

    /** 用户 1.0 已完成任务总数（用于 list 接口 total 累加）。 */
    public long countCompleted(String clerkUserId) {
        Long count = taskMapper.selectCount(completedQuery(clerkUserId));
        return count == null ? 0L : count;
    }

    /** 拉取 1.0 已完成任务（按完成时间倒序），以 VerlaConversationVO 形式返回。 */
    public List<VerlaConversationVO> listAsConversations(String clerkUserId, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<TaskEntity> rows = taskMapper.selectList(completedQuery(clerkUserId)
                .orderByDesc(TaskEntity::getFinishTime)
                .orderByDesc(TaskEntity::getId)
                .last("LIMIT " + limit + " OFFSET " + Math.max(0, offset)));
        List<VerlaConversationVO> vos = new ArrayList<>(rows.size());
        for (TaskEntity t : rows) {
            vos.add(toConversationVO(t));
        }
        return vos;
    }

    private LambdaQueryWrapper<TaskEntity> completedQuery(String clerkUserId) {
        return new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getClerkUserId, clerkUserId)
                .eq(TaskEntity::getStatus, STATUS_COMPLETED)
                .eq(TaskEntity::getIsDeleted, 0);
    }

    // ====================================================================
    // 详情 / 鉴权
    // ====================================================================

    /** 详情接口：返回单个虚拟 conversation（含归属与状态校验）。 */
    public VerlaConversationVO getConversation(String clerkUserId, long legacyTaskId) {
        return toConversationVO(requireOwnedCompleted(clerkUserId, legacyTaskId));
    }

    /**
     * 鉴权 + 状态校验：任务必须存在、未删除、归属当前用户且已完成。
     * 任何不满足都按"任务不存在"处理，避免越权探测。
     */
    public TaskEntity requireOwnedCompleted(String clerkUserId, long legacyTaskId) {
        TaskEntity t = taskMapper.selectById(legacyTaskId);
        if (t == null || (t.getIsDeleted() != null && t.getIsDeleted() == 1)
                || t.getStatus() == null || t.getStatus() != STATUS_COMPLETED) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        if (clerkUserId == null || !clerkUserId.equals(t.getClerkUserId())) {
            throw new BusinessException(ApiCode.NO_PERMISSION);
        }
        return t;
    }

    // ====================================================================
    // 消息（user + assistant 各 1 条，不入库）
    // ====================================================================

    public List<VerlaMessageVO> buildMessages(TaskEntity t) {
        long virtualCid = LegacyConversationIdCodec.encode(t.getId());

        StringBuilder userText = new StringBuilder();
        if (StringUtils.isNotBlank(t.getTaskDesc())) {
            userText.append(t.getTaskDesc().trim());
        } else if (StringUtils.isNotBlank(t.getTaskTitle())) {
            userText.append(t.getTaskTitle().trim());
        }
        if (StringUtils.isNotBlank(t.getSpecialInstructions())) {
            if (userText.length() > 0) {
                userText.append("\n\n");
            }
            userText.append("特殊要求：").append(t.getSpecialInstructions().trim());
        }

        List<VerlaMessageVO> messages = new ArrayList<>(2);
        if (userText.length() > 0) {
            messages.add(VerlaMessageVO.builder()
                    .messageId(VerlaPublicIdVoSupport.message(virtualCid * 10 + 1, true))
                    .role("user")
                    .text(userText.toString())
                    .createdAt(t.getCreatedAt())
                    .build());
        }
        messages.add(VerlaMessageVO.builder()
                .messageId(VerlaPublicIdVoSupport.message(virtualCid * 10 + 2, true))
                .role("assistant")
                .text(ASSISTANT_TEXT)
                .createdAt(lastActiveAt(t))
                .build());
        return messages;
    }

    // ====================================================================
    // Artifact（task_outputs，outputType=1）
    // ====================================================================

    public List<VerlaArtifactVO> buildArtifacts(long legacyTaskId) {
        List<TaskOutputEntity> outs = taskOutputMapper.selectList(
                new LambdaQueryWrapper<TaskOutputEntity>()
                        .eq(TaskOutputEntity::getTaskId, legacyTaskId)
                        .eq(TaskOutputEntity::getOutputType, OUTPUT_TYPE_REPORT)
                        .orderByAsc(TaskOutputEntity::getCreatedAt)
                        .orderByAsc(TaskOutputEntity::getId));
        long virtualCid = LegacyConversationIdCodec.encode(legacyTaskId);
        String conversationId = VerlaPublicIdVoSupport.conversation(virtualCid, true);

        List<VerlaArtifactVO> artifacts = new ArrayList<>(outs.size());
        for (TaskOutputEntity o : outs) {
            artifacts.add(toArtifactVO(o, legacyTaskId, conversationId));
        }
        return artifacts;
    }

    private VerlaArtifactVO toArtifactVO(TaskOutputEntity o, long legacyTaskId, String conversationId) {
        String kind = resolveKind(o.getFormat());
        String rawBody = StringUtils.isNotBlank(o.getContentJson()) ? o.getContentJson() : o.getContentText();
        boolean truncated = false;
        String bodyOrRef = rawBody;
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > maxMessageBytes) {
            bodyOrRef = truncateByBytes(rawBody, maxMessageBytes);
            truncated = true;
        }
        Long sizeBytes = o.getContentText() != null
                ? (long) o.getContentText().getBytes(StandardCharsets.UTF_8).length
                : null;

        return VerlaArtifactVO.builder()
                .artifactUid("legacy_task_" + legacyTaskId + "_out_" + o.getId())
                .conversationId(conversationId)
                .kind(kind)
                .mime(resolveMime(kind))
                .summary(StringUtils.isNotBlank(o.getTitle()) ? truncate(o.getTitle(), 1024) : "作业产出")
                .contentRef(StringUtils.isNotBlank(o.getDownloadUrl()) ? o.getDownloadUrl() : o.getFilePath())
                .bodyOrRef(bodyOrRef)
                .status("READY")
                .sizeBytes(sizeBytes)
                .metaJson(buildArtifactMeta(o, truncated))
                .version(1)
                .updatedAt(o.getUpdatedAt())
                .source(SOURCE_LEGACY)
                .build();
    }

    // ====================================================================
    // runtime-snapshot（工作区 host 启动时拉取的恢复快照）
    // ====================================================================

    /**
     * 合成一个"已完成、无活跃 session"的静态快照，复用真实
     * {@link AssignmentRuntimeSnapshotVO} 契约：payload 直接带上虚拟 messages 与 artifacts，
     * stateEventType 置为 {@code ASSIGNMENT_COMPLETED} 让前端工作区进入"已完成"态。
     */
    public AssignmentRuntimeSnapshotVO buildRuntimeSnapshot(TaskEntity t) {
        long virtualCid = LegacyConversationIdCodec.encode(t.getId());
        AssignmentRuntimeSnapshotVO.Payload payload = AssignmentRuntimeSnapshotVO.Payload.builder()
                .messages(buildMessages(t))
                .stateEventPayload(Map.of())
                .progress(Map.of())
                .agentNodes(List.of())
                .artifacts(buildArtifacts(t.getId()))
                .build();
        return AssignmentRuntimeSnapshotVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(virtualCid, true))
                .resumeAfterEventId(null)
                .stateEventType("ASSIGNMENT_COMPLETED")
                .payload(payload)
                .build();
    }

    // ====================================================================
    // 映射工具
    // ====================================================================

    private VerlaConversationVO toConversationVO(TaskEntity t) {
        long virtualCid = LegacyConversationIdCodec.encode(t.getId());
        LocalDateTime lastActive = lastActiveAt(t);
        return VerlaConversationVO.builder()
                .conversationId(VerlaPublicIdVoSupport.conversation(virtualCid, true))
                .userId(t.getClerkUserId())
                .title(StringUtils.isNotBlank(t.getTaskTitle()) ? t.getTaskTitle() : DEFAULT_TITLE)
                .status("active")
                .dashboardStatus("completed")
                .primaryIntent("ASSIGNMENT")
                .draft(false)
                .turnCount(1)
                .lastTurnId(null)
                .lastMessageAt(lastActive)
                .lastActiveAt(lastActive)
                .createdAt(t.getCreatedAt())
                .source(SOURCE_LEGACY)
                .build();
    }

    private static LocalDateTime lastActiveAt(TaskEntity t) {
        return t.getFinishTime() != null ? t.getFinishTime() : t.getUpdatedAt();
    }

    /**
     * 1.0 {@code task_outputs.format} 枚举：1=Word, 2=PDF, 3=PPT, 4=Markdown。
     * 最终报告（{@code outputType=1}）的 format=4 应走文档编辑器，而不是代码编辑器。
     */
    private static String resolveKind(Integer format) {
        if (format == null) {
            return "document_markdown";
        }
        return switch (format) {
            case 3 -> "slides";
            case 4 -> "document_markdown";
            default -> "document_markdown";
        };
    }

    private static String resolveMime(String kind) {
        return switch (kind) {
            case "slides" -> "application/json";
            case "code" -> "text/plain";
            default -> "text/markdown";
        };
    }

    private String buildArtifactMeta(TaskOutputEntity o, boolean truncated) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", SOURCE_LEGACY);
        if (o.getFormat() != null) {
            meta.put("format", o.getFormat());
        }
        if (o.getPageSize() != null) {
            meta.put("pageSize", o.getPageSize());
        }
        meta.put("truncated", truncated);
        meta.put("outputId", o.getId());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("[Legacy] build artifact meta failed, outputId={}", o.getId(), e);
            return null;
        }
    }

    private static String truncate(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars);
    }

    /** 按 UTF-8 字节上限截断，避免半个多字节字符（会丢弃末尾不完整字符）。 */
    private static String truncateByBytes(String s, int maxBytes) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        // 回退到不切断多字节字符的边界（UTF-8 续字节高位为 10xxxxxx）
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
