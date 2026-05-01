package com.studyagent.common.verla.envelope;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.studyagent.common.verla.enums.VerlaSessionKind;
import com.studyagent.common.verla.enums.VerlaSessionKindDeserializer;
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

    @JsonDeserialize(using = VerlaSessionKindDeserializer.class)
    private VerlaSessionKind kind;

    /**
     * 命令侧带 feature 让 Py 路由（如 assignment / flashcards）
     */
    private String feature;
}
