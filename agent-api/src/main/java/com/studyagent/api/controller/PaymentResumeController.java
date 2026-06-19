package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.response.PaymentResumeResponse;
import com.studyagent.api.service.PaymentResumeApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
public class PaymentResumeController {

    private final PaymentResumeApplicationService paymentResumeApplicationService;

    @PostMapping("/resume/{token}")
    public Result<PaymentResumeResponse> resume(
            @PathVariable("token") String token,
            @RequestAttribute("clerkUserId") String clerkUserId) {
        return Result.success(paymentResumeApplicationService.resume(clerkUserId, token));
    }
}
