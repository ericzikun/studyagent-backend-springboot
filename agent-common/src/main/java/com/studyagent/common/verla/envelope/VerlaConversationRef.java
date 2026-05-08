package com.studyagent.common.verla.envelope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信封中的 conversation 引用块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaConversationRef {

    private Long conversationId;

    /**
     * 命令侧带 userId 给 Py（事件侧可省）
     */
    private String userId;
}
