package com.studyagent.service.domain.task;

import java.util.List;
import java.util.Map;

/**
 * Python后端客户端接口（用于执行Agent任务）
 */
public interface PythonBackendClient {
    /**
     * 执行任务
     */
    void executeTask(TaskId taskId);

    /**
     * 停止任务
     */
    void stopTask(TaskId taskId);

    /**
     * 查询任务队列信息
     */
    TaskQueueInfo getTaskQueueInfo(TaskId taskId);

    /**
     * 批量查询任务队列信息
     */
    java.util.Map<Long, TaskQueueInfo> getTaskQueueBatchInfo(java.util.List<TaskId> taskIds);
    
    /**
     * 追问任务（生成追问问题）
     * @param request 追问请求参数
     * @return 追问响应，包含问题列表和建议
     */
    ClarifyTaskResult clarifyTask(Map<String, Object> request);
    
    /**
     * 追问任务结果
     */
    class ClarifyTaskResult {
        private final List<String> questions;
        private final String suggestions;
        
        public ClarifyTaskResult(List<String> questions, String suggestions) {
            this.questions = questions;
            this.suggestions = suggestions;
        }
        
        public List<String> getQuestions() {
            return questions;
        }
        
        public String getSuggestions() {
            return suggestions;
        }
    }

    class TaskQueueInfo {
        private final int aheadCount;
        private final boolean running;

        public TaskQueueInfo(int aheadCount, boolean running) {
            this.aheadCount = aheadCount;
            this.running = running;
        }

        public int getAheadCount() {
            return aheadCount;
        }

        public boolean isRunning() {
            return running;
        }
    }
}

