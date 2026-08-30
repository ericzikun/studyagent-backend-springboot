package com.studyagent.service.domain.demo.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learning Canvas 知识节点领域对象
 * <p>
 * 对应 demo_learning_node 表。node_type：knowledge / dialogue_step / evidence / quiz / survey /
 * compare / animation / sandbox / image_asset 等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoLearningNode {

    private Long id;
    private Long themeId;
    /** 父节点 id（树形） */
    private Long parentId;
    private String nodeType;
    private String title;
    private String summary;
    /** 生疏 / 理解 / 熟练 */
    private String masteryLevel;
    /** theory / practice / mixed */
    private String learningType;
    /** confirmed / tentative */
    private String certaintyStatus;
    private String startMsgId;
    /** 学习轨迹 JSON（milestones） */
    private String trajectory;
    /** pre-test 题目 JSON（post-test 复用） */
    private String preTestResults;
    /** 扩展字段（组件配置等） */
    private String metaJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
