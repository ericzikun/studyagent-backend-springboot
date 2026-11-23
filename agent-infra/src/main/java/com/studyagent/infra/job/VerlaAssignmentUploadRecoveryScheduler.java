package com.studyagent.infra.job;

import com.studyagent.service.application.verla.VerlaAttachmentService;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerlaAssignmentUploadRecoveryScheduler {

    private final VerlaAttachmentRepository attachmentRepository;
    private final VerlaAttachmentService attachmentService;

    @Scheduled(fixedDelayString = "${verla.attachment.assignment-recovery-delay-ms:60000}")
    public void finalizeStoredAssignmentUploads() {
        for (VerlaAttachment attachment :
                attachmentRepository.findAssignmentUploadsToFinalize(LocalDateTime.now().minusMinutes(2), 100)) {
            try {
                attachmentService.recoverAssignmentUpload(attachment.getUserId(), attachment.getObjectId());
            } catch (RuntimeException ex) {
                log.warn("[Verla/attachment] Assignment recovery failed objectId={}: {}",
                        attachment.getObjectId(), ex.getMessage());
            }
        }
    }
}
