package com.studyagent.service.application.verla;

import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.verla.enums.VerlaAttachmentStatus;
import com.studyagent.service.application.verla.dto.FileChatAnalysisState;
import com.studyagent.service.application.verla.dto.FileChatAnalysisStatus;
import com.studyagent.service.application.verla.dto.FileChatPanelFileView;
import com.studyagent.service.application.verla.dto.FileChatPanelMessageView;
import com.studyagent.service.application.verla.dto.FileChatPanelState;
import com.studyagent.service.application.verla.dto.FileChatPanelView;
import com.studyagent.service.domain.verla.VerlaAttachment;
import com.studyagent.service.domain.verla.VerlaMessage;
import com.studyagent.service.domain.verla.repo.VerlaAttachmentRepository;
import com.studyagent.service.domain.verla.repo.VerlaMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VerlaFileChatService {

    private final VerlaConversationService conversationService;
    private final VerlaAttachmentRepository attachmentRepository;
    private final VerlaMessageRepository messageRepository;

    public FileChatPanelView getPanel(String userId, Long conversationId, String objectId, Long cursor, int limit) {
        if (objectId == null || objectId.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "objectId required");
        }
        conversationService.getOwned(userId, conversationId);
        return getPanelInternal(conversationId, objectId, cursor, limit);
    }

    /** Admin 运维只读：跳过 conversation 所有权校验。 */
    public FileChatPanelView getPanelForAdmin(Long conversationId, String objectId, Long cursor, int limit) {
        if (objectId == null || objectId.isBlank()) {
            throw new BusinessException(ApiCode.PARAM_ERROR, "objectId required");
        }
        conversationService.getForInternal(conversationId);
        return getPanelInternal(conversationId, objectId, cursor, limit);
    }

    private FileChatPanelView getPanelInternal(Long conversationId, String objectId, Long cursor, int limit) {
        VerlaAttachment attachment = attachmentRepository.findByObjectId(objectId);
        if (attachment == null || !conversationId.equals(attachment.getConversationId())) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND, "attachment");
        }

        int safeLimit = Math.max(1, Math.min(limit, 100));
        FileChatPanelState storedState = VerlaFileChatMetadataHelper.readAttachmentState(attachment);
        FileChatPanelState effectiveState = applyAttachmentStatusFallback(attachment, storedState);
        List<VerlaMessage> rows = messageRepository.findFileChatByCursor(conversationId, objectId, cursor, safeLimit);
        List<FileChatPanelMessageView> messages = rows.stream()
                .map(this::toMessageView)
                .toList();

        Long nextCursor = messages.size() == safeLimit
                ? messages.get(messages.size() - 1).getMessageId()
                : null;

        return FileChatPanelView.builder()
                .file(FileChatPanelFileView.builder()
                        .objectId(attachment.getObjectId())
                        .name(attachment.getFilename())
                        .mimeType(attachment.getMime())
                        .sizeBytes(attachment.getSizeBytes())
                        .extractStatus(attachment.getStatus())
                        .build())
                .analysis(effectiveState.getAnalysis())
                .suggestedQuestions(effectiveState.getSuggestedQuestions())
                .messages(messages)
                .nextCursor(nextCursor)
                .build();
    }

    private FileChatPanelState applyAttachmentStatusFallback(VerlaAttachment attachment, FileChatPanelState storedState) {
        VerlaAttachmentStatus status = parseAttachmentStatus(attachment.getStatus());
        if (status == VerlaAttachmentStatus.FAILED) {
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.builder()
                            .status(FileChatAnalysisStatus.FAILED)
                            .text(attachment.getParseError() == null ? "" : attachment.getParseError())
                            .build())
                    .suggestedQuestions(List.of())
                    .updatedAt(storedState == null ? null : storedState.getUpdatedAt())
                    .build();
        }
        if (status != VerlaAttachmentStatus.PARSED) {
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.pending())
                    .suggestedQuestions(List.of())
                    .updatedAt(storedState == null ? null : storedState.getUpdatedAt())
                    .build();
        }
        if (storedState == null) {
            return readyStateFromAttachmentSummary(attachment);
        }
        String storedText = storedState.getAnalysis() == null ? null : storedState.getAnalysis().getText();
        if ((storedText == null || storedText.isBlank())
                && attachment.getSummary() != null && !attachment.getSummary().isBlank()) {
            return FileChatPanelState.builder()
                    .analysis(FileChatAnalysisState.builder()
                            .status(FileChatAnalysisStatus.READY)
                            .text(attachment.getSummary())
                            .build())
                    .suggestedQuestions(storedState.getSuggestedQuestions() == null ? List.of() : storedState.getSuggestedQuestions())
                    .updatedAt(storedState.getUpdatedAt())
                    .build();
        }
        return storedState;
    }

    private FileChatPanelState readyStateFromAttachmentSummary(VerlaAttachment attachment) {
        boolean ready = attachment.getSummary() != null && !attachment.getSummary().isBlank();
        return FileChatPanelState.builder()
                .analysis(FileChatAnalysisState.builder()
                        .status(ready ? FileChatAnalysisStatus.READY : FileChatAnalysisStatus.PENDING)
                        .text(ready ? attachment.getSummary() : "")
                        .build())
                .suggestedQuestions(List.of())
                .build();
    }

    private VerlaAttachmentStatus parseAttachmentStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return VerlaAttachmentStatus.valueOf(status);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private FileChatPanelMessageView toMessageView(VerlaMessage message) {
        return FileChatPanelMessageView.builder()
                .messageId(message.getId())
                .role(message.getRole())
                .text(message.getTextContent())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
