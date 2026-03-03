package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ExportFileRequest;
import com.studyagent.api.dto.request.UploadFileRequest;
import com.studyagent.api.dto.response.ExportFileResponse;
import com.studyagent.api.dto.response.UploadFileResponse;
import com.studyagent.common.api.ApiCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RestController
@RequestMapping("/v1/file")
@RequiredArgsConstructor
public class MockFileController {

    private final MockStateStore store;

    @PostMapping("/upload")
    public Result<UploadFileResponse> uploadFile(@Valid @RequestBody UploadFileRequest request) {
        String filename = (request.getFilename() == null || request.getFilename().isBlank())
            ? "uploaded_file.txt"
            : request.getFilename();

        MockStateStore.MockFileRecord record = store.saveFile(filename, request.getRawBody());

        UploadFileResponse response = UploadFileResponse.builder()
            .objectId(record.objectId)
            .build();
        return Result.success(response);
    }

    @PostMapping("/export")
    public Result<ExportFileResponse> exportFile(@Valid @RequestBody ExportFileRequest request) {
        return store.findFile(request.getObjectId())
            .map(file -> Result.success(ExportFileResponse.builder()
                .contentType(file.contentType)
                .rawBody(file.rawBody)
                .build()))
            .orElseGet(() -> Result.error(ApiCode.PARAM_ERROR));
    }

    @GetMapping("/download/{objectId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String objectId) {
        return store.findFile(objectId)
            .map(file -> {
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(file.rawBody);
                } catch (Exception e) {
                    bytes = file.rawBody.getBytes(StandardCharsets.UTF_8);
                }

                String filename = URLEncoder.encode(file.filename, StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                    .body((Resource) new ByteArrayResource(bytes));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
