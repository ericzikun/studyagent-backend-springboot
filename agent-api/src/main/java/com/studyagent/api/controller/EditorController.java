package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.EditorExportPdfRequest;
import com.studyagent.api.dto.request.EditorExportWordRequest;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.common.api.ApiCode;
import com.studyagent.api.dto.request.SaveEditorContentRequest;
import com.studyagent.api.dto.response.EditorExportResponse;
import com.studyagent.api.dto.response.GetEditorContentResponse;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskMapper;
import com.studyagent.infra.mapper.TaskOutputMapper;
import com.studyagent.service.application.EditorExportApplicationService;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import com.google.gson.Gson;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 文本编辑器控制器
 * 权限：管理员可访问任意任务的编辑器，普通用户仅能访问自己的任务
 */
@RestController
@RequestMapping("/v1/editor")
@RequiredArgsConstructor
public class EditorController {
    
    private final TaskOutputMapper taskOutputMapper;
    private final TaskMapper taskMapper;
    private final UserRepository userRepository;
    private final EditorExportApplicationService editorExportApplicationService;
    private final Gson gson = new Gson();
    
    @GetMapping("/content/{taskId}")
    public Result<GetEditorContentResponse> getEditorContent(
            @PathVariable String taskId,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(taskId);
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<GetEditorContentResponse>) permissionError;
        }
        // 查找该任务的终稿输出（output_type=1）
        TaskOutputEntity taskOutput = taskOutputMapper.selectOne(
            new LambdaQueryWrapper<TaskOutputEntity>()
                .eq(TaskOutputEntity::getTaskId, internalTaskId)
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
            @PathVariable String taskId,
            @RequestBody SaveEditorContentRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(taskId);
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<Map<String, Object>>) permissionError;
        }
        // 查找或创建终稿输出记录
        TaskOutputEntity taskOutput = taskOutputMapper.selectOne(
            new LambdaQueryWrapper<TaskOutputEntity>()
                .eq(TaskOutputEntity::getTaskId, internalTaskId)
                .eq(TaskOutputEntity::getOutputType, 1)
                .orderByDesc(TaskOutputEntity::getUpdatedAt)
                .last("LIMIT 1")
        );
        
        boolean created = false;
        if (taskOutput == null) {
            // 创建新记录
            taskOutput = new TaskOutputEntity();
            taskOutput.setTaskId(internalTaskId);
            taskOutput.setOutputType(1); // 终稿
            taskOutput.setFormat(4); // Markdown/JSON
            taskOutput.setFilePath("/outputs/task_" + internalTaskId + "/editor_content.json");
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
        response.put("task_id", taskId);  // 返回原始编码 ID 供前端使用
        response.put("id", taskOutput.getId());
        response.put("title", taskOutput.getTitle());
        response.put("saved", true);
        response.put("created", created);
        
        return Result.success(response);
    }

    @PostMapping("/export/word")
    public Result<EditorExportResponse> exportWord(
            @Valid @RequestBody EditorExportWordRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<EditorExportResponse>) permissionError;
        }

        try {
            var result = editorExportApplicationService.exportWord(request.getRawBody(), request.getFilename());
            return Result.success(EditorExportResponse.builder()
                    .objectId(result.objectId())
                    .downloadUrl(result.downloadUrl())
                    .filename(result.filename())
                    .build());
        } catch (IllegalArgumentException e) {
            return Result.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @PostMapping("/export/pdf")
    public Result<EditorExportResponse> exportPdf(
            @Valid @RequestBody EditorExportPdfRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(request.getTaskId());
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<EditorExportResponse>) permissionError;
        }

        try {
            var result = editorExportApplicationService.convertWordToPdf(
                    request.getSourceObjectId(),
                    request.getFilename());
            return Result.success(EditorExportResponse.builder()
                    .objectId(result.objectId())
                    .downloadUrl(result.downloadUrl())
                    .filename(result.filename())
                    .build());
        } catch (IllegalArgumentException e) {
            return Result.error(ApiCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return Result.error(ApiCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    
    /**
     * 校验编辑器访问权限：管理员可访问任意任务，普通用户仅能访问自己的任务
     * @return 若有权限问题返回错误 Result，否则返回 null
     */
    private Result<?> checkEditorPermission(Long taskId, String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        TaskEntity taskEntity = taskMapper.selectById(taskId);
        if (taskEntity == null || (taskEntity.getIsDeleted() != null && taskEntity.getIsDeleted() == 1)) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        boolean isAdmin = userRepository.findByClerkUserId(clerkUserId)
                .map(User::getIsAdmin)
                .orElse(false);
        if (!isAdmin && !clerkUserId.equals(taskEntity.getClerkUserId())) {
            return Result.error(ApiCode.NO_PERMISSION);
        }
        return null;
    }
}
