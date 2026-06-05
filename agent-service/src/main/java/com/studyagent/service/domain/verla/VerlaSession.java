package com.studyagent.service.domain.verla;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Verla Session 领域对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSession {

    private Long id;
    private Long conversationId;
    private Long turnId;
    /** PLAN / AGENT / MATERIALS */
    private String kind;
    private String featureCode;
    /**
     * 本 session 商业化扣费流水 ID（quota_ledger.id）。
     * <p>
     * V2 verla 链路在 {@code spawnAssignmentClarifySession（finalize）/ spawnCapabilitySession}
     * 派发命令前同事务回填；run session 自 clarify session 继承。为空表示 admin / 白名单 / 未启用配额。
     * 失败 / 取消时按此反查并退款，保证幂等。
     */
    private Long quotaLedgerId;
    /** 本 session 扣费数量（次 / 字），仅用于审计与排错；为空同上。 */
    private Long quotaAmount;
    /** CREATED / DISPATCHING / RUNNING / SUCCEEDED / FAILED / CANCELLING / CANCELLED */
    private String status;
    private String correlationId;
    private String contextRefJson;
    private String resultJson;
    private String errorJson;
    private Long expectedSeq;
    private Long lastEventSeq;
    private LocalDateTime lastProgressAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
