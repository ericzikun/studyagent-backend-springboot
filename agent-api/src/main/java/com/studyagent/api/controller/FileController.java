package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.request.ExportFileRequest;
import com.studyagent.api.dto.request.UploadFileRequest;
import com.studyagent.api.dto.response.ExportFileResponse;
import com.studyagent.api.dto.response.UploadFileResponse;
import com.studyagent.service.application.FileApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件管理控制器
 */
@RestController
@RequestMapping("/v1/file")
@RequiredArgsConstructor
public class FileController {
    
    private final FileApplicationService fileApplicationService;
    
    @PostMapping("/upload")
    public Result<UploadFileResponse> uploadFile(
            @Valid @RequestBody UploadFileRequest request) {
        // 使用请求中的文件名，如果没有则使用默认值
        String filename = request.getFilename() != null && !request.getFilename().trim().isEmpty()
            ? request.getFilename() : "uploaded_file";
        
        String objectId = fileApplicationService.uploadFile(request.getRawBody(), filename);
        
        UploadFileResponse response = UploadFileResponse.builder()
            .objectId(objectId)
            .build();
        
        return Result.success(response);
    }
    
    @PostMapping("/export")
    public Result<ExportFileResponse> exportFile(
            @Valid @RequestBody ExportFileRequest request) {
        String base64Content = fileApplicationService.exportFile(request.getObjectId());
        
        ExportFileResponse response = ExportFileResponse.builder()
            .contentType("application/octet-stream")
            .rawBody(base64Content)
            .build();
        
        return Result.success(response);
    }
    
    /**
     * 下载文件（通过 objectId）
     * @param objectId 文件对象ID
     * @return 文件资源
     */
    @GetMapping("/download/{objectId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String objectId) {
        try {
            // 获取文件信息
            com.studyagent.service.domain.file.File file = fileApplicationService.getFileByObjectId(objectId);
            if (file == null) {
                return ResponseEntity.notFound().build();
            }
            
            // 读取文件内容
            Path filePath = Paths.get(file.getStoragePath());
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }
            
            byte[] fileContent = Files.readAllBytes(filePath);
            Resource resource = new ByteArrayResource(fileContent);
            
            // 获取文件名和 MIME 类型
            String filename = file.getOriginalFilename();
            String contentType = file.getContentType();
            if (contentType == null || contentType.isEmpty()) {
                contentType = "application/octet-stream";
            }
            
            // 对文件名进行 URL 编码
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
                
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

