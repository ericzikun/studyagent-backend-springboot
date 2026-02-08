package com.studyagent.service.domain.file;

/**
 * OSS 存储服务接口
 * 
 * 定义在领域层，由基础设施层实现
 * 遵循依赖倒置原则（DIP）
 */
public interface OssStorageService {
    
    /**
     * 异步上传文件到 OSS
     * 
     * @param fileContent 文件内容
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     */
    void uploadFileAsync(byte[] fileContent, String objectId, String filename);
    
    /**
     * 异步上传本地文件到 OSS
     * 
     * @param localFilePath 本地文件路径
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     */
    void uploadLocalFileAsync(String localFilePath, String objectId, String filename);
    
    /**
     * 同步上传文件到 OSS
     * 
     * @param fileContent 文件内容
     * @param objectId 文件对象ID
     * @param filename 原始文件名
     * @return OSS 对象的 Key，如果上传失败返回 null
     */
    String uploadFile(byte[] fileContent, String objectId, String filename);
    
    /**
     * 获取文件的 OSS 访问 URL
     * 
     * @param ossKey OSS 对象的 Key
     * @return 访问 URL
     */
    String getOssUrl(String ossKey);
    
    /**
     * 检查 OSS 是否已启用
     * 
     * @return true 如果已启用
     */
    boolean isEnabled();
}
