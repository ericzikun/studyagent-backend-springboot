package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ConsumeTriggerRequest;
import com.studyagent.api.dto.request.SubmitFeedbackRequest;
import com.studyagent.api.dto.response.ConsumeTriggerResponse;
import com.studyagent.api.dto.response.SubmitFeedbackResponse;
import com.studyagent.api.service.FeedbackApplicationService;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户反馈控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackApplicationService feedbackApplicationService;

    /**
     * 消费触发：判断是否应弹窗，返回 session 和模板信息
     */
    @PostMapping("/triggers/consume")
    public Result<ConsumeTriggerResponse> consumeTrigger(
            @Valid @RequestBody ConsumeTriggerRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        ConsumeTriggerResponse response = feedbackApplicationService.consumeTrigger(
                clerkUserId,
                request.getTriggerCode(),
                request.getSubjectType(),
                request.getSubjectId(),
                request.getSourcePage());
        return Result.success(response);
    }

    /**
     * 提交反馈
     */
    @PostMapping("/submissions")
    public Result<SubmitFeedbackResponse> submitFeedback(
            @Valid @RequestBody SubmitFeedbackRequest request,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isEmpty()) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }
        SubmitFeedbackResponse response = feedbackApplicationService.submitFeedback(
                clerkUserId,
                request.getPromptSessionId(),
                request.getScore(),
                request.getVote(),
                request.getSelectedTagCodes(),
                request.getComment(),
                request.getContact());
        return Result.success(response);
    }
}
