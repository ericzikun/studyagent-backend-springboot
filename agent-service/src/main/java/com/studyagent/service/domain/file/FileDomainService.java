package com.studyagent.service.domain.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 文件领域服务
 */
@Slf4j
@Service
public class FileDomainService {
    
    /**
     * 验证文件是否可以上传
     */
    public void validateFile(byte[] fileContent, String filename) {
        if (fileContent == null || fileContent.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        
        // 文件大小限制：100MB
        long maxSize = 100 * 1024 * 1024;
        if (fileContent.length > maxSize) {
            throw new IllegalArgumentException("文件大小不能超过100MB");
        }
        
        log.debug("文件验证通过: {}, 大小: {} bytes", filename, fileContent.length);
    }
    
    /**
     * 解码 base64 文件内容
     */
    public byte[] decodeBase64(String base64Content) {
        try {
            return Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的 base64 编码", e);
        }
    }
    
    /**
     * 编码文件内容为 base64
     */
    public String encodeBase64(byte[] fileContent) {
        return Base64.getEncoder().encodeToString(fileContent);
    }
    
    /**
     * 生成文件对象ID（UUID）
     */
    public String generateObjectId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * 从文件名提取扩展名
     */
    public String extractFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}

