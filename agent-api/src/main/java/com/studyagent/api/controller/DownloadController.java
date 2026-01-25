package com.studyagent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.infra.entity.TaskOutputEntity;
import com.studyagent.infra.mapper.TaskOutputMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件下载控制器
 */
@RestController
@RequestMapping("/v1/download")
@RequiredArgsConstructor
public class DownloadController {
    
    private final TaskOutputMapper taskOutputMapper;
    
    @Value("${file.storage.output-path:./storage/outputs}")
    private String outputPath;
    
    @GetMapping("/output/{taskId}/{filename}")
    public ResponseEntity<Resource> downloadOutput(
            @PathVariable Long taskId,
            @PathVariable String filename) {
        try {
            // 1. 查询 TaskOutput 记录
            TaskOutputEntity taskOutput = taskOutputMapper.selectOne(
                new LambdaQueryWrapper<TaskOutputEntity>()
                    .eq(TaskOutputEntity::getTaskId, taskId)
                    .and(wrapper -> wrapper
                        .like(TaskOutputEntity::getFilePath, filename)
                        .or()
                        .like(TaskOutputEntity::getFilePath, "/" + filename)
                    )
            );
            
            String content = null;
            
            // 2. 优先从数据库字段读取
            if (taskOutput != null) {
                if (taskOutput.getContentText() != null && !taskOutput.getContentText().isEmpty()) {
                    content = taskOutput.getContentText();
                } else if (taskOutput.getLogText() != null && !taskOutput.getLogText().isEmpty()) {
                    content = taskOutput.getLogText();
                }
            }
            
            // 3. 如果数据库字段为空，从文件路径读取
            if (content == null) {
                Path filePath = Paths.get(outputPath, "task_" + taskId, filename);
                if (filePath.toFile().exists()) {
                    content = new String(java.nio.file.Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                } else {
                    return ResponseEntity.notFound().build();
                }
            }
            
            // 4. 返回文件内容（txt格式）
            String downloadFilename = filename;
            if (!downloadFilename.endsWith(".txt")) {
                downloadFilename = filename.replaceFirst("\\.[^.]+$", "") + ".txt";
            }
            
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            Resource resource = new ByteArrayResource(contentBytes);
            
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8) + "\"")
                .body(resource);
                
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

