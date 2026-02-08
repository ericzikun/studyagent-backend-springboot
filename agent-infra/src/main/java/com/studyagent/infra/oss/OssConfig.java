package com.studyagent.infra.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {
    
    /**
     * 是否启用 OSS 备份
     */
    private boolean enabled = false;
    
    /**
     * OSS 访问域名
     * 例如：oss-cn-hangzhou.aliyuncs.com
     */
    private String endpoint;
    
    /**
     * AccessKey ID
     */
    private String accessKeyId;
    
    /**
     * AccessKey Secret
     */
    private String accessKeySecret;
    
    /**
     * Bucket 名称
     */
    private String bucketName;
    
    /**
     * 存储目录前缀
     * 例如：studyagent/uploads
     */
    private String pathPrefix = "studyagent/uploads";
}
