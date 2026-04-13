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

import java.io.IOException;
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
        byte[] fileContent = fileDomainService.decodeBase64(base64Content);
        return uploadFileBytes(fileContent, filename);
    }

    /**
     * 直接上传字节数组文件，供服务端生成/转换后的文件落库使用。
     */
    @Transactional
    public String uploadFileBytes(byte[] fileContent, String filename) {
        fileDomainService.validateFile(fileContent, filename);

        String objectId = fileDomainService.generateObjectId();
        String storagePath = saveFileToStorage(fileContent, objectId, filename);

        File file = File.builder()
            .objectId(objectId)
            .originalFilename(filename)
            .fileExtension(fileDomainService.extractFileExtension(filename))
            .contentType(detectContentType(filename))
            .fileSize((long) fileContent.length)
            .storagePath(storagePath)
            .storageType(1)
            .markdownStatus(0)
            .build();

        File savedFile = fileRepository.save(file);
        log.info("文件上传成功: objectId={}, filename={}", savedFile.getObjectId(), filename);

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
     * 读取文件字节：优先本地 storagePath，不存在则按 ossKey 从 OSS 回源
     */
    public Optional<byte[]> loadFileContent(String objectId) {
        return loadFileContent(getFileByObjectId(objectId));
    }

    public Optional<byte[]> loadFileContent(File file) {
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(readFileBytes(file));
        } catch (IOException e) {
            log.warn("读取文件失败 objectId={}: {}", file.getObjectId(), e.getMessage());
            return Optional.empty();
        }
    }

    private byte[] readFileBytes(File file) throws IOException {
        String pathStr = file.getStoragePath();
        if (pathStr != null && !pathStr.isBlank()) {
            Path p = Paths.get(pathStr);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        }
        String ossKey = file.getOssKey();
        if (ossKey != null && !ossKey.isBlank() && ossStorageService.isEnabled()) {
            byte[] fromOss = ossStorageService.getObjectBytes(ossKey.trim());
            if (fromOss != null && fromOss.length > 0) {
                log.info("已从 OSS 回源文件: objectId={}, ossKey={}", file.getObjectId(), ossKey);
                return fromOss;
            }
            log.warn("OSS 中未找到或内容为空: objectId={}, ossKey={}", file.getObjectId(), ossKey);
        }
        throw new IOException("File not found locally or in OSS: objectId=" + file.getObjectId());
    }
    
    /**
     * 导出文件
     * @param objectId 文件对象ID
     * @return base64编码的文件内容
     */
    public String exportFile(String objectId) {
        Optional<File> fileOpt = fileRepository.findByObjectId(objectId);
        if (fileOpt.isEmpty()) {
            throw new RuntimeException("File not found: " + objectId);
        }

        File file = fileOpt.get();

        try {
            byte[] fileContent = readFileBytes(file);
            return fileDomainService.encodeBase64(fileContent);
        } catch (IOException e) {
            log.error("Failed to read file: objectId={}", file.getObjectId(), e);
            throw new RuntimeException("Failed to read file", e);
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
            log.error("Failed to save file", e);
            throw new RuntimeException("Failed to save file", e);
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
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
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
