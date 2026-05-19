package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.SaveEditorContentRequest;
import com.studyagent.api.dto.response.GetEditorContentResponse;
import com.studyagent.api.util.TaskIdEncoder;
import com.studyagent.common.api.ApiCode;
import com.studyagent.infra.entity.TaskEditorContentEntity;
import com.studyagent.infra.entity.TaskEditorContentVersionEntity;
import com.studyagent.infra.entity.TaskEntity;
import com.studyagent.infra.mapper.TaskEditorContentMapper;
import com.studyagent.infra.mapper.TaskEditorContentVersionMapper;
import com.studyagent.infra.mapper.TaskMapper;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import com.studyagent.service.domain.user.User;
import com.studyagent.service.domain.user.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用编辑器控制器
 * 权限：管理员可访问任意任务的编辑器，普通用户仅能访问自己的任务
 */
@RestController
@RequestMapping("/v1/editor")
@RequiredArgsConstructor
public class EditorController {

    private static final String DEFAULT_EDITOR_KIND = "document";
    private static final int MAX_EDITOR_VERSIONS = 3;
    private static final Set<String> SUPPORTED_EDITOR_KINDS = Set.of(
            "document", "slides", "code"
    );

    private final TaskEditorContentMapper taskEditorContentMapper;
    private final TaskEditorContentVersionMapper taskEditorContentVersionMapper;
    private final TaskMapper taskMapper;
    private final UserRepository userRepository;
    private final Gson gson = new Gson();

    @GetMapping("/content/{taskId}")
    public Result<GetEditorContentResponse> getEditorContent(
            @PathVariable String taskId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(taskId);
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        String editorKind = normalizeEditorKind(kind);
        if (editorKind == null) {
            return Result.error(ApiCode.BAD_REQUEST, "Unsupported editor kind: " + kind);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<GetEditorContentResponse>) permissionError;
        }

        TaskEditorContentEntity editorContent = taskEditorContentMapper.selectOne(
            new LambdaQueryWrapper<TaskEditorContentEntity>()
                .eq(TaskEditorContentEntity::getTaskId, internalTaskId)
                .eq(TaskEditorContentEntity::getEditorKind, editorKind)
                .orderByDesc(TaskEditorContentEntity::getUpdatedAt)
                .last("LIMIT 1")
        );

        if (editorContent == null || editorContent.getContentJson() == null) {
            GetEditorContentResponse response = GetEditorContentResponse.builder()
                .taskId(taskId)
                .kind(editorKind)
                .exists(false)
                .content(null)
                .build();
            return Result.success(response);
        }

        Map<String, Object> content = null;
        Map<String, Object> meta = null;
        boolean parseError = false;
        try {
            content = gson.fromJson(editorContent.getContentJson(), Map.class);
        } catch (Exception e) {
            parseError = true;
        }
        if (editorContent.getMetaJson() != null && !editorContent.getMetaJson().isBlank()) {
            try {
                meta = gson.fromJson(editorContent.getMetaJson(), Map.class);
            } catch (Exception e) {
                parseError = true;
            }
        }
        Integer versionNo = findLatestVersionNo(editorContent.getId());

        GetEditorContentResponse response = GetEditorContentResponse.builder()
            .taskId(taskId)
            .kind(editorKind)
            .exists(true)
            .id(editorContent.getId())
            .title(editorContent.getTitle())
            .content(content)
            .meta(meta)
            .versionNo(versionNo)
            .sourceArtifactUid(editorContent.getSourceArtifactUid())
            .sourceObjectId(editorContent.getSourceObjectId())
            .parseError(parseError)
            .build();

        return Result.success(response);
    }

    @PutMapping("/content/{taskId}")
    public Result<Map<String, Object>> saveEditorContent(
            @PathVariable String taskId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestBody SaveEditorContentRequest request,
            @RequestAttribute(value = "clerkUserId", required = false) String clerkUserId) {
        Long internalTaskId = TaskIdEncoder.decode(taskId);
        if (internalTaskId == null) {
            return Result.error(ApiCode.TASK_NOT_FOUND);
        }
        String editorKind = normalizeEditorKind(kind);
        if (editorKind == null) {
            return Result.error(ApiCode.BAD_REQUEST, "Unsupported editor kind: " + kind);
        }
        Result<?> permissionError = checkEditorPermission(internalTaskId, clerkUserId);
        if (permissionError != null) {
            return (Result<Map<String, Object>>) permissionError;
        }

        TaskEditorContentEntity editorContent = taskEditorContentMapper.selectOne(
            new LambdaQueryWrapper<TaskEditorContentEntity>()
                .eq(TaskEditorContentEntity::getTaskId, internalTaskId)
                .eq(TaskEditorContentEntity::getEditorKind, editorKind)
                .orderByDesc(TaskEditorContentEntity::getUpdatedAt)
                .last("LIMIT 1")
        );

        boolean created = false;
        LocalDateTime now = LocalDateTime.now();
        if (editorContent == null) {
            editorContent = new TaskEditorContentEntity();
            editorContent.setTaskId(internalTaskId);
            editorContent.setEditorKind(editorKind);
            editorContent.setContentSchemaVersion(resolveContentSchemaVersion(request.getContentSchemaVersion()));
            editorContent.setCreatedBy(clerkUserId);
            editorContent.setCreatedAt(now);
            created = true;
        }

        editorContent.setContentJson(gson.toJson(request.getContent()));
        if (request.getMeta() != null) {
            editorContent.setMetaJson(gson.toJson(request.getMeta()));
        }
        if (request.getSourceArtifactUid() != null && !request.getSourceArtifactUid().isBlank()) {
            editorContent.setSourceArtifactUid(request.getSourceArtifactUid().trim());
        }
        if (request.getSourceObjectId() != null && !request.getSourceObjectId().isBlank()) {
            editorContent.setSourceObjectId(request.getSourceObjectId().trim());
        }
        editorContent.setTitle(resolveTitle(request, editorContent.getTitle()));
        editorContent.setContentSchemaVersion(resolveContentSchemaVersion(request.getContentSchemaVersion()));
        editorContent.setUpdatedBy(clerkUserId);
        editorContent.setUpdatedAt(now);

        if (created) {
            taskEditorContentMapper.insert(editorContent);
        } else {
            taskEditorContentMapper.updateById(editorContent);
        }

        String saveSource = resolveSaveSource(request.getSaveSource());
        Integer latestVersionNo = findLatestVersionNo(editorContent.getId());
        int responseVersionNo = latestVersionNo;
        if (!"autosave".equals(saveSource)) {
            int nextVersionNo = latestVersionNo + 1;
            TaskEditorContentVersionEntity versionEntity = new TaskEditorContentVersionEntity();
            versionEntity.setEditorContentId(editorContent.getId());
            versionEntity.setVersionNo(nextVersionNo);
            versionEntity.setContentJson(editorContent.getContentJson());
            versionEntity.setMetaJson(editorContent.getMetaJson());
            versionEntity.setSaveSource(saveSource);
            versionEntity.setCreatedBy(clerkUserId);
            versionEntity.setCreatedAt(now);
            taskEditorContentVersionMapper.insert(versionEntity);
            pruneOldVersions(editorContent.getId());
            responseVersionNo = nextVersionNo;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("task_id", taskId);
        response.put("kind", editorKind);
        response.put("id", editorContent.getId());
        response.put("title", editorContent.getTitle());
        response.put("versionNo", responseVersionNo);
        response.put("updatedAt", now);
        response.put("saved", true);
        response.put("created", created);

        return Result.success(response);
    }

    private String normalizeEditorKind(String kind) {
        String normalized = kind == null || kind.isBlank()
                ? DEFAULT_EDITOR_KIND
                : kind.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_EDITOR_KINDS.contains(normalized) ? normalized : null;
    }

    private Integer findLatestVersionNo(Long editorContentId) {
        TaskEditorContentVersionEntity latest = taskEditorContentVersionMapper.selectOne(
                new LambdaQueryWrapper<TaskEditorContentVersionEntity>()
                        .eq(TaskEditorContentVersionEntity::getEditorContentId, editorContentId)
                        .orderByDesc(TaskEditorContentVersionEntity::getVersionNo)
                        .last("LIMIT 1")
        );
        return latest != null && latest.getVersionNo() != null ? latest.getVersionNo() : 0;
    }

    private void pruneOldVersions(Long editorContentId) {
        if (editorContentId == null) {
            return;
        }
        java.util.List<TaskEditorContentVersionEntity> versions = taskEditorContentVersionMapper.selectList(
                new LambdaQueryWrapper<TaskEditorContentVersionEntity>()
                        .eq(TaskEditorContentVersionEntity::getEditorContentId, editorContentId)
                        .orderByDesc(TaskEditorContentVersionEntity::getVersionNo)
        );
        if (versions.size() <= MAX_EDITOR_VERSIONS) {
            return;
        }
        versions.stream()
                .skip(MAX_EDITOR_VERSIONS)
                .map(TaskEditorContentVersionEntity::getId)
                .filter(java.util.Objects::nonNull)
                .forEach(taskEditorContentVersionMapper::deleteById);
    }

    private Integer resolveContentSchemaVersion(Integer version) {
        return version != null && version > 0 ? version : 1;
    }

    private String resolveSaveSource(String saveSource) {
        if (saveSource == null || saveSource.isBlank()) {
            return "manual_save";
        }
        return saveSource.trim();
    }

    private String resolveTitle(SaveEditorContentRequest request, String existingTitle) {
        if (request.getTitle() != null && !request.getTitle().trim().isEmpty()) {
            return request.getTitle().trim();
        }
        if (request.getContent() != null) {
            Object title = request.getContent().get("title");
            if (title instanceof String titleStr && !titleStr.trim().isEmpty()) {
                return titleStr.trim();
            }
        }
        if (existingTitle != null && !existingTitle.isBlank()) {
            return existingTitle;
        }
        return "Untitled";
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
