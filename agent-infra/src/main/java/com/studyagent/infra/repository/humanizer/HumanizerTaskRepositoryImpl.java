package com.studyagent.infra.repository.humanizer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.infra.entity.HumanizerTaskEntity;
import com.studyagent.infra.mapper.HumanizerTaskMapper;
import lombok.RequiredArgsConstructor;
import com.studyagent.common.datetime.DateTimeFormats;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class HumanizerTaskRepositoryImpl {

    private final HumanizerTaskMapper mapper;

    public void insert(HumanizerTaskEntity entity) {
        mapper.insert(entity);
    }

    public void updateById(HumanizerTaskEntity entity) {
        entity.setUpdatedAt(DateTimeFormats.now());
        mapper.updateById(entity);
    }

    public HumanizerTaskEntity findById(Long id) {
        return mapper.selectById(id);
    }

    /**
     * 分页查询用户任务列表（按创建时间倒序）
     * 只查精简字段，不查大文本
     */
    public Page<HumanizerTaskEntity> findByUserPaged(String clerkUserId, String taskType, String source, int page, int size) {
        Page<HumanizerTaskEntity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<HumanizerTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HumanizerTaskEntity::getClerkUserId, clerkUserId);
        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(HumanizerTaskEntity::getTaskType, taskType);
        }
        if (source != null && !source.isEmpty()) {
            wrapper.eq(HumanizerTaskEntity::getSource, source);
        }
        // 不查大字段，列表只需要摘要
        wrapper.select(
                HumanizerTaskEntity::getId,
                HumanizerTaskEntity::getClerkUserId,
                HumanizerTaskEntity::getTaskType,
                HumanizerTaskEntity::getTaskName,
                HumanizerTaskEntity::getInputText,  // 需要截取前50字符
                HumanizerTaskEntity::getStatus,
                HumanizerTaskEntity::getProbability,
                HumanizerTaskEntity::getLabel,
                HumanizerTaskEntity::getTotalSentences,
                HumanizerTaskEntity::getCompletedSentences,
                HumanizerTaskEntity::getResultText,  // 需要截取前50字符
                HumanizerTaskEntity::getElapsedSeconds,
                HumanizerTaskEntity::getErrorMessage,
                HumanizerTaskEntity::getCreatedAt
        );
        wrapper.orderByDesc(HumanizerTaskEntity::getCreatedAt);
        return mapper.selectPage(pageParam, wrapper);
    }

    /**
     * 捞待处理任务（PENDING，先进先出）
     */
    public List<HumanizerTaskEntity> findPendingTasks(String taskType, int limit) {
        LambdaQueryWrapper<HumanizerTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HumanizerTaskEntity::getStatus, "PENDING");
        if (taskType != null) {
            wrapper.eq(HumanizerTaskEntity::getTaskType, taskType);
        }
        wrapper.orderByAsc(HumanizerTaskEntity::getCreatedAt);
        wrapper.last("LIMIT " + limit);
        return mapper.selectList(wrapper);
    }

    /**
     * 原子抢占任务
     */
    public boolean claimTask(Long id) {
        return mapper.claimTask(id) > 0;
    }

    /**
     * 回收超时的 PROCESSING 任务
     */
    public int recoverTimeoutTasks(int timeoutMinutes, int maxRetry) {
        return mapper.recoverTimeoutTasks(timeoutMinutes, maxRetry);
    }

    /**
     * 原子取消任务：只有 PENDING/PROCESSING 才能取消
     */
    public boolean cancelTask(Long id) {
        return mapper.cancelTask(id) > 0;
    }

    /**
     * 统计某类型在某任务之前排队的任务数（PENDING + PROCESSING，按创建时间排在前面的）
     */
    public int countQueueAhead(String taskType, Long taskId) {
        LambdaQueryWrapper<HumanizerTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HumanizerTaskEntity::getTaskType, taskType);
        wrapper.in(HumanizerTaskEntity::getStatus, "PENDING", "PROCESSING");
        wrapper.lt(HumanizerTaskEntity::getId, taskId);
        return Math.toIntExact(mapper.selectCount(wrapper));
    }

    /**
     * 统计某类型正在处理的任务数
     */
    public int countProcessing(String taskType) {
        LambdaQueryWrapper<HumanizerTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HumanizerTaskEntity::getTaskType, taskType);
        wrapper.eq(HumanizerTaskEntity::getStatus, "PROCESSING");
        return Math.toIntExact(mapper.selectCount(wrapper));
    }

    /**
     * 查询用户是否有匹配 result_hash 的已完成 HUMANIZE 任务
     * 用于 DETECT 时判断是否使用宽松阈值
     */
    public boolean existsHumanizeResultHash(String clerkUserId, String resultHash) {
        if (resultHash == null || resultHash.isEmpty()) return false;
        LambdaQueryWrapper<HumanizerTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HumanizerTaskEntity::getClerkUserId, clerkUserId);
        wrapper.eq(HumanizerTaskEntity::getTaskType, "HUMANIZE");
        wrapper.eq(HumanizerTaskEntity::getStatus, "COMPLETED");
        wrapper.eq(HumanizerTaskEntity::getResultHash, resultHash);
        wrapper.last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }
}
