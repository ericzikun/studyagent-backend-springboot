package com.studyagent.service.domain.task;

import java.util.List;
import java.util.Optional;

/**
 * 任务Repository接口
 */
public interface TaskRepository {
    Optional<Task> findById(TaskId id);
    Task save(Task task);
    void delete(TaskId id);
    List<Task> findByClerkUserId(String clerkUserId);
    List<Task> findAll();
    List<Task> findByStatus(TaskStatus status);
    List<Task> findByKeyword(String keyword);
    
    /**
     * 分页查询任务列表（支持排序和筛选）
     * @param clerkUserId 用户ID（可选）
     * @param status 任务状态（可选）
     * @param keyword 关键词（可选）
     * @param order 排序方式：1-最新优先(createdAt DESC), 2-最旧优先(createdAt ASC), 3-标题A-Z, 4-标题Z-A, 5-更新时间最新优先(updatedAt DESC)
     * @param pageNo 页码（从1开始）
     * @param pageSize 每页大小
     * @return 分页结果，包含任务列表和总数
     */
    PageResult<Task> findWithPagination(String clerkUserId, TaskStatus status, String keyword, Integer order, Integer pageNo, Integer pageSize);
    
    /**
     * 分页结果
     */
    class PageResult<T> {
        private final List<T> items;
        private final Long total;
        
        public PageResult(List<T> items, Long total) {
            this.items = items;
            this.total = total;
        }
        
        public List<T> getItems() {
            return items;
        }
        
        public Long getTotal() {
            return total;
        }
    }
}

