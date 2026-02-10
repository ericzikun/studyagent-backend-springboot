package com.studyagent.infra.repository.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyagent.infra.converter.TaskConverter;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.service.domain.task.Task;
import com.studyagent.service.domain.task.TaskId;
import com.studyagent.service.domain.task.TaskRepository;
import com.studyagent.service.domain.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 任务Repository实现
 */
@Repository
@RequiredArgsConstructor
public class TaskRepositoryImpl implements TaskRepository {
    
    private final TaskMapper taskMapper;
    private final TaskConverter converter;
    
    @Override
    public Optional<Task> findById(TaskId id) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getId, id.getValue());
        notDeleted(wrapper);
        TaskEntity entity = taskMapper.selectOne(wrapper);
        return Optional.ofNullable(converter.toDomain(entity));
    }
    
    @Override
    public Task save(Task task) {
        TaskEntity entity = converter.toEntity(task);
        
        if (entity.getId() == null) {
            // 新建
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.insert(entity);
        } else {
            // 更新
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
        }
        
        return converter.toDomain(entity);
    }
    
    /** 未删除条件：兼容 is_deleted 为 NULL 的旧数据 */
    private static void notDeleted(LambdaQueryWrapper<TaskEntity> wrapper) {
        wrapper.and(w -> w.isNull(TaskEntity::getIsDeleted).or().eq(TaskEntity::getIsDeleted, 0));
    }

    @Override
    public void delete(TaskId id) {
        taskMapper.deleteById(id.getValue());
    }

    @Override
    public void logicalDelete(TaskId id) {
        taskMapper.update(null, new LambdaUpdateWrapper<TaskEntity>()
                .eq(TaskEntity::getId, id.getValue())
                .set(TaskEntity::getIsDeleted, 1)
                .set(TaskEntity::getUpdatedAt, java.time.LocalDateTime.now()));
    }
    
    @Override
    public List<Task> findByClerkUserId(String clerkUserId) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getClerkUserId, clerkUserId);
        notDeleted(wrapper);
        List<TaskEntity> entities = taskMapper.selectList(wrapper);
        return entities.stream()
            .map(converter::toDomain)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Task> findAll() {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        notDeleted(wrapper);
        List<TaskEntity> entities = taskMapper.selectList(wrapper);
        return entities.stream()
            .map(converter::toDomain)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Task> findByStatus(TaskStatus status) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getStatus, status.getCode());
        notDeleted(wrapper);
        List<TaskEntity> entities = taskMapper.selectList(wrapper);
        return entities.stream()
            .map(converter::toDomain)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<Task> findByKeyword(String keyword) {
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .like(TaskEntity::getTaskTitle, keyword)
                .or()
                .like(TaskEntity::getTaskDesc, keyword);
        notDeleted(wrapper);
        List<TaskEntity> entities = taskMapper.selectList(wrapper);
        return entities.stream()
            .map(converter::toDomain)
            .collect(Collectors.toList());
    }
    
    @Override
    public long countByStatus(String clerkUserId, TaskStatus status) {
        LambdaQueryWrapper<TaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        notDeleted(queryWrapper);
        if (clerkUserId != null && !clerkUserId.isEmpty()) {
            queryWrapper.eq(TaskEntity::getClerkUserId, clerkUserId);
        }
        if (status != null) {
            queryWrapper.eq(TaskEntity::getStatus, status.getCode());
        }
        Long count = taskMapper.selectCount(queryWrapper);
        return count == null ? 0L : count;
    }
    
    @Override
    public TaskRepository.PageResult<Task> findWithPagination(
            String clerkUserId, 
            TaskStatus status, 
            String keyword, 
            Integer order, 
            Integer pageNo, 
            Integer pageSize) {
        
        // 构建查询条件
        LambdaQueryWrapper<TaskEntity> queryWrapper = new LambdaQueryWrapper<>();
        notDeleted(queryWrapper);
        
        // 用户ID筛选
        if (clerkUserId != null && !clerkUserId.isEmpty()) {
            queryWrapper.eq(TaskEntity::getClerkUserId, clerkUserId);
        }
        
        // 状态筛选
        if (status != null) {
            queryWrapper.eq(TaskEntity::getStatus, status.getCode());
        }
        
        // 关键词搜索（标题或描述）
        if (keyword != null && !keyword.isEmpty()) {
            queryWrapper.and(wrapper -> wrapper
                .like(TaskEntity::getTaskTitle, keyword)
                .or()
                .like(TaskEntity::getTaskDesc, keyword)
            );
        }
        
        // 排序
        // order: 1-最新优先(createdAt DESC), 2-最旧优先(createdAt ASC), 3-标题A-Z, 4-标题Z-A, 5-更新时间最新优先(updatedAt DESC)
        if (order != null) {
            switch (order) {
                case 1: // 最新优先（创建时间降序）
                    queryWrapper.orderByDesc(TaskEntity::getCreatedAt);
                    break;
                case 2: // 最旧优先（创建时间升序）
                    queryWrapper.orderByAsc(TaskEntity::getCreatedAt);
                    break;
                case 3: // 标题A-Z
                    queryWrapper.orderByAsc(TaskEntity::getTaskTitle);
                    break;
                case 4: // 标题Z-A
                    queryWrapper.orderByDesc(TaskEntity::getTaskTitle);
                    break;
                case 5: // 更新时间最新优先
                    queryWrapper.orderByDesc(TaskEntity::getUpdatedAt);
                    break;
                default: // 默认按创建时间降序（最新优先）
                    queryWrapper.orderByDesc(TaskEntity::getCreatedAt);
                    break;
            }
        } else {
            // 默认按创建时间降序（最新优先）
            queryWrapper.orderByDesc(TaskEntity::getCreatedAt);
        }
        
        // 创建分页对象
        Page<TaskEntity> page = new Page<>(pageNo, pageSize);
        
        // 执行分页查询
        IPage<TaskEntity> pageResult = taskMapper.selectPage(page, queryWrapper);
        
        // 转换为领域对象
        List<Task> tasks = pageResult.getRecords().stream()
            .map(converter::toDomain)
            .collect(Collectors.toList());
        
        return new TaskRepository.PageResult<>(tasks, pageResult.getTotal());
    }
}

