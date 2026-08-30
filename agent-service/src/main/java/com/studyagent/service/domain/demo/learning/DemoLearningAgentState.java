package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas Agent 状态领域对象
 * <p>
 * 对应 demo_learning_agent_state 表，theme_id 为业务主键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningAgentState {

    private Long themeId;
    /** 当前焦点节点 */
    private Long currentFocusNodeId;
    /** 待讲队列 JSON */
    private String pendingOutline;
    /** pre_test / socratic_guiding / post_test / crystallization / apply / ... */
    private String currentLearningStage;
    private LocalDateTime updatedAt;
}
