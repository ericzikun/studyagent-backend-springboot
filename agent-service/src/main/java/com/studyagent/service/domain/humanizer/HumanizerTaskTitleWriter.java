package com.studyagent.service.domain.humanizer;

/**
 * 回写独立 Humanizer/检测任务标题的端口。
 * <p>
 * Verla 事件 handler 位于 agent-service，无法直接依赖 agent-infra 的
 * {@code HumanizerTaskRepositoryImpl}；通过本接口由 infra 实现，遵循
 * service 定义端口、infra 实现的依赖方向。
 */
public interface HumanizerTaskTitleWriter {

    /**
     * 将标题写入 humanizer_tasks（仅当当前标题为空，幂等且抗乱序）。
     *
     * @param taskId   humanizer_tasks 主键
     * @param taskName ConversationTitleService 生成的标题
     * @return 受影响行数（0 表示任务不存在或标题已存在）
     */
    int updateTaskName(Long taskId, String taskName);
}
