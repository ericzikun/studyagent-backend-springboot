package com.studyagent.common.verla.envelope;

import com.studyagent.common.verla.enums.VerlaSessionKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信封中的 session 引用块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerlaSessionRef {

    private Long sessionId;

    private VerlaSessionKind kind;

    /**
     * 命令侧带 feature 让 Py 路由（如 assignment / flashcards）
     */
    private String feature;
}
