package com.studyagent.api.dto.response;

import com.studyagent.api.common.Meta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交任务响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitTaskResponse {
    private Meta meta;
    /** 对外暴露的 taskId（Sqids 编码） */
    private String taskId;

    /**
     * 任务提交额度信息（普通用户有值，管理员为 null；不限额时也为 null）
     */
    private SubmitQuotaInfo quota;
}

