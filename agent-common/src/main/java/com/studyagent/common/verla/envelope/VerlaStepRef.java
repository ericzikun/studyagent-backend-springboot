package com.studyagent.common.verla.envelope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事件信封中的 step 引用块（可选，仅多 sub-agent 流式时存在）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaStepRef {

    private String stepId;

    private String stepKind;

    /**
     * step 内 token 流序号（仅排障用，保序仍以 eventSeq 为准）
     */
    private Integer stepSeq;
}
