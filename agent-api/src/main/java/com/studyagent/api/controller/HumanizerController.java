package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.HumanizerRequest;
import com.studyagent.api.dto.response.HumanizerSubmitResult;
import com.studyagent.api.dto.response.HumanizerTaskListResponse;
import com.studyagent.api.dto.response.HumanizerTaskResponse;
import com.studyagent.api.service.HumanizerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Humanizer / AI 检测 控制器（异步队列版）
 * <p>
 * POST /v1/humanizer/detect         — 提交 AI 检测任务，返回 taskId
 * POST /v1/humanizer/process        — 提交改写任务，返回 taskId
 * GET  /v1/humanizer/tasks/{id}     — 查询任务详情（前端轮询）
 * GET  /v1/humanizer/tasks          — 查询用户任务列表（分页）
 */
@Slf4j
@RestController
@RequestMapping("/v1/humanizer")
@RequiredArgsConstructor
public class HumanizerController {

    private final HumanizerApplicationService humanizerApplicationService;

    /**
     * 提交 AI 检测任务
     */
    @PostMapping("/detect")
    public Result<HumanizerTaskResponse> submitDetect(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("提交 DETECT 任务: userId={}, textLength={}", clerkUserId, request.getText().length());
        HumanizerSubmitResult result = humanizerApplicationService.submitTask(clerkUserId, "DETECT", request.getText());
        return Result.success(result.response(), result.quotaConsumed());
    }

    /**
     * 提交 Humanize 改写任务
     */
    @PostMapping("/process")
    public Result<HumanizerTaskResponse> submitHumanize(
            @RequestBody @Valid HumanizerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        log.info("提交 HUMANIZE 任务: userId={}, textLength={}", clerkUserId, request.getText().length());
        HumanizerSubmitResult result = humanizerApplicationService.submitTask(clerkUserId, "HUMANIZE", request.getText());
        return Result.success(result.response(), result.quotaConsumed());
    }

    /**
     * 查询任务详情（前端轮询用，返回完整数据含 sentencesJson / resultText）
     */
    @GetMapping("/tasks/{id}")
    public Result<HumanizerTaskResponse> getTask(
            @PathVariable Long id,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        HumanizerTaskResponse response = humanizerApplicationService.getTask(id, clerkUserId);
        return Result.success(response);
    }

    /**
     * 查询用户任务列表（分页，精简字段）
     *
     * @param taskType 可选: DETECT / HUMANIZE，不传查全部
     * @param page     页码，从 1 开始，默认 1
     * @param size     每页条数，默认 10
     */
    @GetMapping("/tasks")
    public Result<HumanizerTaskListResponse> listTasks(
            @RequestParam(required = false) String taskType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        HumanizerTaskListResponse response = humanizerApplicationService.listTasks(clerkUserId, taskType, page, size);
        return Result.success(response);
    }
}
