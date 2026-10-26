package com.studyagent.api.dto.request;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThaiOutputLanguageValidationTest {
    @Test
    void legacyTaskRequestsAcceptThaiAndStillRejectUnknownLocaleCodes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (Class<?> request : List.of(SubmitTaskRequest.class, SaveDraftRequest.class,
                    ClarifyTaskRequest.class)) {
                assertThat(validator.validateValue(request, "outputLanguage", "th"))
                        .as(request.getSimpleName()).isEmpty();
                assertThat(validator.validateValue(request, "outputLanguage", "klingon"))
                        .as(request.getSimpleName()).isNotEmpty();
            }
        }
    }
}
