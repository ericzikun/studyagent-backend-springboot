package com.studyagent.service.application;

import com.studyagent.service.domain.file.File;
import com.studyagent.service.domain.file.FileRepository;
import com.studyagent.service.domain.file.FileDomainService;
import com.studyagent.service.domain.file.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 文件应用服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileApplicationService {
    
    private final FileRepository fileRepository;
    private final FileDomainService fileDomainService;
    private final OssStorageService ossStorageService;
    
    @Value("${file.storage.upload-path:./storage/uploads}")
    private String uploadPath;
    
    /**
     * 上传文件
     * @param base64Content base64编码的文件内容
     * @param filename 文件名
     * @return objectId
     */
    @Transactional
    public String uploadFile(String base64Content, String filename) {
        // 1. 解码 base64
        byte[] fileContent = fileDomainService.decodeBase64(base64Content);
        
        // 2. 验证文件
        fileDomainService.validateFile(fileContent, filename);
        
        // 3. 生成 objectId
        String objectId = fileDomainService.generateObjectId();
        
        // 4. 保存文件到存储
        String storagePath = saveFileToStorage(fileContent, objectId, filename);
        
        // 5. 创建文件领域模型
        File file = File.builder()
            .objectId(objectId)
            .originalFilename(filename)
            .fileExtension(fileDomainService.extractFileExtension(filename))
            .contentType(detectContentType(filename))
            .fileSize((long) fileContent.length)
            .storagePath(storagePath)
            .storageType(1) // 1-本地存储
            .markdownStatus(0) // 0-未转换
            .build();
        
        // 6. 保存文件记录
        File savedFile = fileRepository.save(file);
        
        log.info("文件上传成功: objectId={}, filename={}", savedFile.getObjectId(), filename);
        
        // 7. 异步上传到阿里云 OSS 作为备份
        if (ossStorageService.isEnabled()) {
            log.info("开始异步上传文件到 OSS: objectId={}", savedFile.getObjectId());
            ossStorageService.uploadFileAsync(fileContent, savedFile.getObjectId(), filename);
        }
        
        return savedFile.getObjectId();
    }
    
    /**
     * 根据 objectId 获取文件
     * @param objectId 文件对象ID
     * @return 文件领域模型，如果不存在则返回 null
     */
    public File getFileByObjectId(String objectId) {
        Optional<File> fileOpt = fileRepository.findByObjectId(objectId);
        return fileOpt.orElse(null);
    }
    
    /**
     * 导出文件
     * @param objectId 文件对象ID
     * @return base64编码的文件内容
     */
    public String exportFile(String objectId) {
        Optional<File> fileOpt = fileRepository.findByObjectId(objectId);
        if (fileOpt.isEmpty()) {
            throw new RuntimeException("文件不存在: " + objectId);
        }
        
        File file = fileOpt.get();
        
        // 从存储读取文件
        try {
            Path filePath = Paths.get(file.getStoragePath());
            byte[] fileContent = Files.readAllBytes(filePath);
            return fileDomainService.encodeBase64(fileContent);
        } catch (Exception e) {
            log.error("读取文件失败: {}", file.getStoragePath(), e);
            throw new RuntimeException("读取文件失败", e);
        }
    }
    
    /**
     * 保存文件到存储
     * 参考 Python 后端实现：按年月日组织文件夹，保留文件扩展名
     */
    private String saveFileToStorage(byte[] fileContent, String objectId, String filename) {
        try {
            // 1. 创建年月日文件夹路径（格式：YYYY/MM/DD）
            LocalDate now = LocalDate.now();
            String datePath = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            
            // 2. 构建完整的上传目录路径
            Path uploadBaseDir = Paths.get(uploadPath);
            Path storageDir = uploadBaseDir.resolve(datePath);
            Files.createDirectories(storageDir);
            
            log.debug("文件保存目录: {}", storageDir);
            
            // 3. 清理文件名（移除不安全字符，保留扩展名）
            String safeFilename = sanitizeFilename(filename);
            
            // 4. 确保文件名包含扩展名
            String fileExtension = fileDomainService.extractFileExtension(filename);
            if (!safeFilename.contains(".") && !fileExtension.isEmpty()) {
                safeFilename = safeFilename + "." + fileExtension;
            }
            
            // 5. 构建文件路径：{objectId}_{safeFilename}
            Path filePath = storageDir.resolve(objectId + "_" + safeFilename);
            Files.write(filePath, fileContent);
            
            log.info("文件保存成功: {}", filePath);
            
            // 返回绝对路径，确保 Python 后端能够找到文件
            return filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("保存文件失败", e);
            throw new RuntimeException("保存文件失败", e);
        }
    }
    
    /**
     * 清理文件名，移除不安全字符（参考 Python 后端的 sanitize_filename）
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return "uploaded_file";
        }
        
        // 移除路径分隔符和其他危险字符
        String safe = filename.replaceAll("[/\\\\:*?\"<>|]", "_");
        
        // 移除控制字符（ASCII 0-31 和 127）
        safe = safe.replaceAll("[\\x00-\\x1F\\x7F]", "");
        
        // 限制长度（保留扩展名）
        int maxLength = 255;
        if (safe.length() > maxLength) {
            int lastDot = safe.lastIndexOf(".");
            if (lastDot > 0) {
                String name = safe.substring(0, lastDot);
                String ext = safe.substring(lastDot);
                name = name.substring(0, Math.min(name.length(), maxLength - ext.length()));
                safe = name + ext;
            } else {
                safe = safe.substring(0, maxLength);
            }
        }
        
        return safe.trim();
    }
    
    /**
     * 检测文件类型
     */
    private String detectContentType(String filename) {
        String ext = fileDomainService.extractFileExtension(filename);
        return switch (ext.toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "doc", "docx" -> "application/msword";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt", "pptx" -> ext.equalsIgnoreCase("ppt") 
                ? "application/vnd.ms-powerpoint" 
                : "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }
}

