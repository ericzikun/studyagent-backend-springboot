package com.studyagent.infra.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.studyagent.service.domain.file.FileRepository;
import com.studyagent.service.domain.file.OssStorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 阿里云 OSS 服务
 * 
 * 实现 OssStorageService 接口，提供文件上传到 OSS 的功能
 * 作为本地存储的备份
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssService implements OssStorageService {
    
    private final OssConfig ossConfig;
    private final FileRepository fileRepository;
    
    private OSS ossClient;
    
    /**
     * 初始化 OSS 客户端
     */
    @PostConstruct
    public void init() {
        if (!ossConfig.isEnabled()) {
            log.info("OSS 备份未启用");
            return;
        }
        
        // 验证必要的配置
        if (isBlank(ossConfig.getEndpoint()) || 
            isBlank(ossConfig.getAccessKeyId()) || 
            isBlank(ossConfig.getAccessKeySecret()) ||
            isBlank(ossConfig.getBucketName())) {
            log.warn("OSS 配置不完整，已禁用 OSS 备份功能");
            ossConfig.setEnabled(false);
            return;
        }
        
        try {
            // 创建 OSS 客户端
            ossClient = new OSSClientBuilder().build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret()
            );
            
            // 验证 Bucket 是否存在
            boolean bucketExists = ossClient.doesBucketExist(ossConfig.getBucketName());
            if (!bucketExists) {
                log.error("OSS Bucket 不存在: {}", ossConfig.getBucketName());
                ossConfig.setEnabled(false);
                return;
            }
            
            log.info("OSS 客户端初始化成功，Endpoint: {}, Bucket: {}", 
                ossConfig.getEndpoint(), ossConfig.getBucketName());
                
        } catch (Exception e) {
            log.error("OSS 客户端初始化失败", e);
            ossConfig.setEnabled(false);
        }
    }
    
    /**
     * 关闭 OSS 客户端
     */
    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OSS 客户端已关闭");
        }
    }
    
    /**
     * 异步上传文件到 OSS（字节数组）
     * 
     * @param fileContent 文件内容
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     */
    @Override
    @Async("ossUploadExecutor")
    public void uploadFileAsync(byte[] fileContent, String objectId, String filename) {
        if (!ossConfig.isEnabled() || ossClient == null) {
            return;
        }
        
        try {
            String ossKey = generateOssKey(objectId, filename);
            
            log.info("开始上传文件到 OSS: objectId={}, ossKey={}", objectId, ossKey);
            
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                PutObjectRequest putObjectRequest = new PutObjectRequest(
                    ossConfig.getBucketName(),
                    ossKey,
                    inputStream
                );
                
                PutObjectResult result = ossClient.putObject(putObjectRequest);
                
                log.info("文件上传到 OSS 成功: objectId={}, ossKey={}, ETag={}", 
                    objectId, ossKey, result.getETag());
                
                // 上传成功后，更新数据库中的 ossKey
                boolean updated = fileRepository.updateOssKey(objectId, ossKey);
                if (updated) {
                    log.info("已更新文件的 OSS Key: objectId={}, ossKey={}", objectId, ossKey);
                } else {
                    log.warn("更新文件的 OSS Key 失败（文件可能不存在）: objectId={}", objectId);
                }
            }
            
        } catch (OSSException oe) {
            log.error("OSS 服务端错误: objectId={}, ErrorCode={}, ErrorMessage={}, RequestId={}", 
                objectId, oe.getErrorCode(), oe.getErrorMessage(), oe.getRequestId());
        } catch (ClientException ce) {
            log.error("OSS 客户端错误: objectId={}, ErrorMessage={}", 
                objectId, ce.getMessage());
        } catch (Exception e) {
            log.error("上传文件到 OSS 失败: objectId={}", objectId, e);
        }
    }
    
    /**
     * 异步上传本地文件到 OSS
     * 
     * @param localFilePath 本地文件路径
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     */
    @Override
    @Async("ossUploadExecutor")
    public void uploadLocalFileAsync(String localFilePath, String objectId, String filename) {
        if (!ossConfig.isEnabled() || ossClient == null) {
            return;
        }
        
        try {
            File localFile = new File(localFilePath);
            if (!localFile.exists()) {
                log.warn("本地文件不存在，跳过 OSS 上传: {}", localFilePath);
                return;
            }
            
            String ossKey = generateOssKey(objectId, filename);
            
            log.info("开始上传本地文件到 OSS: localPath={}, ossKey={}", localFilePath, ossKey);
            
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                ossConfig.getBucketName(),
                ossKey,
                localFile
            );
            
            PutObjectResult result = ossClient.putObject(putObjectRequest);
            
            log.info("本地文件上传到 OSS 成功: localPath={}, ossKey={}, ETag={}", 
                localFilePath, ossKey, result.getETag());
            
            // 上传成功后，更新数据库中的 ossKey
            boolean updated = fileRepository.updateOssKey(objectId, ossKey);
            if (updated) {
                log.info("已更新文件的 OSS Key: objectId={}, ossKey={}", objectId, ossKey);
            } else {
                log.warn("更新文件的 OSS Key 失败（文件可能不存在）: objectId={}", objectId);
            }
                
        } catch (OSSException oe) {
            log.error("OSS 服务端错误: localPath={}, ErrorCode={}, ErrorMessage={}", 
                localFilePath, oe.getErrorCode(), oe.getErrorMessage());
        } catch (ClientException ce) {
            log.error("OSS 客户端错误: localPath={}, ErrorMessage={}", 
                localFilePath, ce.getMessage());
        } catch (Exception e) {
            log.error("上传本地文件到 OSS 失败: localPath={}", localFilePath, e);
        }
    }
    
    /**
     * 同步上传文件到 OSS（字节数组）
     * 
     * @param fileContent 文件内容
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     * @return OSS 对象的 Key，如果上传失败返回 null
     */
    @Override
    public String uploadFile(byte[] fileContent, String objectId, String filename) {
        if (!ossConfig.isEnabled() || ossClient == null) {
            return null;
        }
        
        try {
            String ossKey = generateOssKey(objectId, filename);
            
            log.info("开始同步上传文件到 OSS: objectId={}, ossKey={}", objectId, ossKey);
            
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                PutObjectRequest putObjectRequest = new PutObjectRequest(
                    ossConfig.getBucketName(),
                    ossKey,
                    inputStream
                );
                
                PutObjectResult result = ossClient.putObject(putObjectRequest);
                
                log.info("文件同步上传到 OSS 成功: objectId={}, ossKey={}, ETag={}", 
                    objectId, ossKey, result.getETag());
                    
                return ossKey;
            }
            
        } catch (Exception e) {
            log.error("同步上传文件到 OSS 失败: objectId={}", objectId, e);
            return null;
        }
    }
    
    /**
     * 生成 OSS 对象的 Key
     * 格式：{pathPrefix}/{YYYY/MM/DD}/{objectId}_{filename}
     * 
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     * @return OSS Key
     */
    private String generateOssKey(String objectId, String filename) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String prefix = ossConfig.getPathPrefix();
        
        // 确保 prefix 不以 / 开头或结尾
        if (prefix != null && !prefix.isEmpty()) {
            prefix = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
            return String.format("%s/%s/%s_%s", prefix, datePath, objectId, sanitizeFilename(filename));
        } else {
            return String.format("%s/%s_%s", datePath, objectId, sanitizeFilename(filename));
        }
    }
    
    /**
     * 清理文件名中的特殊字符
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return "uploaded_file";
        }
        // 替换不安全字符
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    
    /**
     * 获取文件的 OSS 访问 URL
     * 
     * @param ossKey OSS 对象的 Key
     * @return 访问 URL
     */
    @Override
    public String getOssUrl(String ossKey) {
        if (!ossConfig.isEnabled() || ossKey == null) {
            return null;
        }
        // 生成公开访问 URL（如果 Bucket 是公开读取的）
        // 格式：https://{bucketName}.{endpoint}/{ossKey}
        String endpoint = ossConfig.getEndpoint().replace("https://", "").replace("http://", "");
        return String.format("https://%s.%s/%s", ossConfig.getBucketName(), endpoint, ossKey);
    }
    
    /**
     * 检查 OSS 是否已启用
     */
    @Override
    public boolean isEnabled() {
        return ossConfig.isEnabled() && ossClient != null;
    }

    @Override
    public byte[] getObjectBytes(String ossKey) {
        if (!isEnabled() || ossKey == null || ossKey.isBlank()) {
            return null;
        }
        try {
            OSSObject object = ossClient.getObject(ossConfig.getBucketName(), ossKey.trim());
            try (InputStream in = object.getObjectContent()) {
                return in.readAllBytes();
            }
        } catch (OSSException oe) {
            log.error("OSS 下载失败: ossKey={}, ErrorCode={}, RequestId={}",
                    ossKey, oe.getErrorCode(), oe.getRequestId());
            return null;
        } catch (ClientException ce) {
            log.error("OSS 客户端错误(下载): ossKey={}, {}", ossKey, ce.getMessage());
            return null;
        } catch (Exception e) {
            log.error("从 OSS 读取对象失败: ossKey={}", ossKey, e);
            return null;
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
