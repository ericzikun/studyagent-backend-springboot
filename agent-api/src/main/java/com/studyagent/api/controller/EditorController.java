package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.SaveEditorContentRequest;
import com.studyagent.api.dto.response.GetEditorContentResponse;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 文本编辑器控制器
 */
@RestController
@RequestMapping("/v1/editor")
@RequiredArgsConstructor
public class EditorController {
    
    private final TaskOutputMapper taskOutputMapper;
    private final Gson gson = new Gson();
    
    @GetMapping("/content/{taskId}")
    public Result<GetEditorContentResponse> getEditorContent(
            @PathVariable Long taskId) {
        // 查找该任务的终稿输出（output_type=1）
        TaskOutputEntity taskOutput = taskOutputMapper.selectOne(
            new LambdaQueryWrapper<TaskOutputEntity>()
                .eq(TaskOutputEntity::getTaskId, taskId)
                .eq(TaskOutputEntity::getOutputType, 1) // 1-终稿
                .orderByDesc(TaskOutputEntity::getUpdatedAt)
                .last("LIMIT 1")
        );
        
        if (taskOutput == null || taskOutput.getContentJson() == null) {
            GetEditorContentResponse response = GetEditorContentResponse.builder()
                .taskId(taskId)
                .exists(false)
                .content(null)
                .build();
            return Result.success(response);
        }
        
        // 解析 content_json
        Map<String, Object> content = null;
        boolean parseError = false;
        try {
            content = gson.fromJson(taskOutput.getContentJson(), Map.class);
        } catch (Exception e) {
            parseError = true;
        }
        
        GetEditorContentResponse response = GetEditorContentResponse.builder()
            .taskId(taskId)
            .exists(true)
            .id(taskOutput.getId())
            .title(taskOutput.getTitle())
            .content(content)
            .parseError(parseError)
            .build();
        
        return Result.success(response);
    }
    
    @PutMapping("/content/{taskId}")
    public Result<Map<String, Object>> saveEditorContent(
            @PathVariable Long taskId,
            @RequestBody SaveEditorContentRequest request) {
        // 查找或创建终稿输出记录
        TaskOutputEntity taskOutput = taskOutputMapper.selectOne(
            new LambdaQueryWrapper<TaskOutputEntity>()
                .eq(TaskOutputEntity::getTaskId, taskId)
                .eq(TaskOutputEntity::getOutputType, 1)
                .orderByDesc(TaskOutputEntity::getUpdatedAt)
                .last("LIMIT 1")
        );
        
        boolean created = false;
        if (taskOutput == null) {
            // 创建新记录
            taskOutput = new TaskOutputEntity();
            taskOutput.setTaskId(taskId);
            taskOutput.setOutputType(1); // 终稿
            taskOutput.setFormat(4); // Markdown/JSON
            taskOutput.setFilePath("/outputs/task_" + taskId + "/editor_content.json");
            created = true;
        }
        
        // 更新内容
        taskOutput.setContentJson(gson.toJson(request.getContent()));
        if (request.getTitle() != null && !request.getTitle().trim().isEmpty()) {
            taskOutput.setTitle(request.getTitle().trim());
        }
        
        taskOutput.setUpdatedAt(LocalDateTime.now());
        
        if (created) {
            taskOutput.setCreatedAt(LocalDateTime.now());
            taskOutputMapper.insert(taskOutput);
        } else {
            taskOutputMapper.updateById(taskOutput);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("task_id", taskId);
        response.put("id", taskOutput.getId());
        response.put("title", taskOutput.getTitle());
        response.put("saved", true);
        response.put("created", created);
        
        return Result.success(response);
    }
}

