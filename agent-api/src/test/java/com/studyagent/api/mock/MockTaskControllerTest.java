package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ExportFileRequest;
import com.studyagent.api.dto.request.SaveDraftRequest;
import com.studyagent.api.dto.request.TaskDetailRequest;
import com.studyagent.api.dto.request.UploadFileRequest;
import com.studyagent.api.dto.response.ExportFileResponse;
import com.studyagent.api.dto.response.SaveDraftResponse;
import com.studyagent.api.dto.response.TaskDetailResponse;
import com.studyagent.api.dto.response.UploadFileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockTaskControllerTest {

    private final MockStateStore store = new MockStateStore();
    private final MockFileController fileController = new MockFileController(store);
    private final MockTaskController taskController = new MockTaskController(store, new MockAuthSupport());

    @Test
    void saveDraftShouldExposeClarifyingAttachmentAndDownloadContent() throws Exception {
        String mainObjectId = upload("assignment-brief.txt", "Main assignment brief");
        String clarifyObjectId = upload("clarify-notes.txt", "Clarify file content for testing");

        SaveDraftRequest saveRequest = new SaveDraftRequest();
        saveRequest.setTaskDesc("Compare policy options and use the follow-up file as context.");
        saveRequest.setSubject(5);
        saveRequest.setAcademicLevel(4);
        saveRequest.setPriorityLevel(1);
        saveRequest.setDueDate(LocalDateTime.now().plusDays(7));
        saveRequest.setObjectIds(List.of(mainObjectId));
        saveRequest.setFormat(List.of(1));
        saveRequest.setCitationStyle(1);
        saveRequest.setPageLength(6);
        saveRequest.setClarifyingQuestions("""
            [{
              "id": "1",
              "question": "Which benchmark should the essay prioritize?",
              "tag": "Scope",
              "answer": "Use the notes in the attached follow-up file.",
              "attachments": [{
                "objectId": "%s",
                "filename": "clarify-notes.txt"
              }]
            }]
            """.formatted(clarifyObjectId));

        Result<SaveDraftResponse> saveResult = taskController.saveDraft(saveRequest, authHeader());

        assertThat(saveResult.getMeta().getStatusCode()).isEqualTo(0);

        TaskDetailRequest detailRequest = new TaskDetailRequest();
        detailRequest.setTaskId(saveResult.getData().getDraftId());
        Result<TaskDetailResponse> detailResult = taskController.getTaskDetail(detailRequest, authHeader());

        assertThat(detailResult.getMeta().getStatusCode()).isEqualTo(0);
        TaskDetailResponse detail = detailResult.getData();
        assertThat(detail.getTaskBaseInfo().getRequirementJson()).contains("clarifyingQuestions");
        assertThat(detail.getClarifyingQuestionList()).singleElement()
            .satisfies(question -> {
                assertThat(question.getId()).isEqualTo("1");
                assertThat(question.getAnswer()).isEqualTo("Use the notes in the attached follow-up file.");
                assertThat(question.getAttachments()).singleElement()
                    .satisfies(attachment -> assertThat(attachment.getObjectId()).isEqualTo(clarifyObjectId));
            });
        assertThat(detail.getUploadedFileInfoList())
            .anySatisfy(file -> {
                assertThat(file.getObjectId()).isEqualTo(mainObjectId);
                assertThat(file.getAttachmentSource()).isEqualTo("TASK");
            })
            .anySatisfy(file -> {
                assertThat(file.getObjectId()).isEqualTo(clarifyObjectId);
                assertThat(file.getAttachmentSource()).isEqualTo("CLARIFY");
                assertThat(file.getClarifyQuestionId()).isEqualTo("1");
                assertThat(file.getDownloadUrl()).isEqualTo("/v1/file/download/" + clarifyObjectId);
            });

        ExportFileRequest exportRequest = new ExportFileRequest();
        exportRequest.setObjectId(clarifyObjectId);
        Result<ExportFileResponse> exportResult = fileController.exportFile(exportRequest);

        assertThat(exportResult.getMeta().getStatusCode()).isEqualTo(0);
        assertThat(decode(exportResult.getData().getRawBody())).isEqualTo("Clarify file content for testing");

        ResponseEntity<Resource> download = fileController.downloadFile(clarifyObjectId);

        assertThat(download.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(StreamUtils.copyToByteArray(download.getBody().getInputStream()), StandardCharsets.UTF_8))
            .isEqualTo("Clarify file content for testing");
    }

    private String upload(String filename, String content) {
        UploadFileRequest uploadRequest = new UploadFileRequest();
        uploadRequest.setFilename(filename);
        uploadRequest.setRawBody(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        Result<UploadFileResponse> result = fileController.uploadFile(uploadRequest);

        assertThat(result.getMeta().getStatusCode()).isEqualTo(0);
        return result.getData().getObjectId();
    }

    private String decode(String rawBody) {
        return new String(Base64.getDecoder().decode(rawBody), StandardCharsets.UTF_8);
    }

    private String authHeader() {
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"user_mock_test\",\"email\":\"mock@test.local\"}".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + header + "." + payload + ".signature";
    }
}
