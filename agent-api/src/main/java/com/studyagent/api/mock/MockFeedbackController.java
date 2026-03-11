package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/v1/feedback")
@RequiredArgsConstructor
public class MockFeedbackController {

    private final MockStateStore store;
    private final MockAuthSupport mockAuthSupport;

    @PostMapping("/triggers/consume")
    public Result<FeedbackTriggerConsumeResponse> consumeTrigger(
        @Valid @RequestBody FeedbackTriggerConsumeRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        try {
            MockStateStore.FeedbackConsumeResult result = store.consumeFeedbackTrigger(
                user.uid(),
                request.getTriggerCode(),
                request.getSubjectType(),
                request.getSubjectId(),
                request.getSourcePage()
            );

            FeedbackTriggerConsumeResponse response = FeedbackTriggerConsumeResponse.builder()
                .shouldPrompt(result.shouldPrompt())
                .triggerCode(request.getTriggerCode())
                .subjectType(request.getSubjectType())
                .subjectId(request.getSubjectId())
                .promptSessionId(result.shouldPrompt() ? result.prompt().promptSessionId : null)
                .build();

            return Result.success(response);
        } catch (IllegalArgumentException ex) {
            return Result.error(ApiCode.PARAM_ERROR.getCode(), ex.getMessage());
        }
    }

    @PostMapping("/submissions")
    public Result<Void> submitFeedback(
        @Valid @RequestBody FeedbackSubmissionRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        MockAuthSupport.MockUser user = mockAuthSupport.requireUser(authorization);
        if (user == null) {
            return Result.error(ApiCode.USER_NOT_LOGGED_IN);
        }

        MockStateStore.FeedbackSubmitResult result;
        try {
            result = store.submitFeedback(
                user.uid(),
                request.getPromptSessionId(),
                request.getScore(),
                request.getVote(),
                request.getSelectedTagCodes(),
                request.getComment(),
                request.getContact()
            );
        } catch (IllegalArgumentException ex) {
            return Result.error(ApiCode.PARAM_ERROR.getCode(), ex.getMessage());
        }

        return switch (result.status()) {
            case SUCCESS -> Result.success(null);
            case PROMPT_NOT_FOUND -> Result.error(ApiCode.PARAM_ERROR.getCode(), result.message());
            case NO_PERMISSION -> Result.error(ApiCode.NO_PERMISSION);
            case ALREADY_SUBMITTED -> Result.error(ApiCode.ILLEGAL_STATE.getCode(), result.message());
        };
    }

    @Data
    static class FeedbackTriggerConsumeRequest {
        @NotBlank
        private String triggerCode;

        @NotBlank
        private String subjectType;

        @NotBlank
        private String subjectId;

        private String sourcePage;
    }

    @Data
    static class FeedbackSubmissionRequest {
        @NotBlank
        private String promptSessionId;

        private Integer score;

        private String vote;

        private List<String> selectedTagCodes = new ArrayList<>();

        private String comment;

        private String contact;
    }

    @Data
    @Builder
    static class FeedbackTriggerConsumeResponse {
        private boolean shouldPrompt;
        private String promptSessionId;
        private String triggerCode;
        private String subjectType;
        private String subjectId;
    }
}
