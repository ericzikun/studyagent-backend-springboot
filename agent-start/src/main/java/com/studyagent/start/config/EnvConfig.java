package com.studyagent.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 加载 .env 文件的配置类
 * Spring Boot 默认不自动加载 .env 文件，需要手动加载
 */
public class EnvConfig implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(EnvConfig.class);
    
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        
        // 查找 .env 文件（在项目根目录）
        File envFile = new File(".env");
        if (!envFile.exists()) {
            // 尝试在父目录查找（如果从 agent-start 目录启动）
            envFile = new File("../.env");
        }
        
        if (envFile.exists() && envFile.isFile()) {
            log.info("找到 .env 文件: {}", envFile.getAbsolutePath());
            Map<String, Object> envProperties = loadEnvFile(envFile);
            
            if (!envProperties.isEmpty()) {
                MapPropertySource propertySource = new MapPropertySource("envFile", envProperties);
                environment.getPropertySources().addFirst(propertySource);
                log.info("已加载 {} 个环境变量从 .env 文件", envProperties.size());
            }
        } else {
            log.debug("未找到 .env 文件，跳过加载");
        }
    }
    
    /**
     * 加载 .env 文件内容
     */
    private Map<String, Object> loadEnvFile(File envFile) {
        Map<String, Object> properties = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            int lineNumber = 0;
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                // 跳过空行和注释
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // 解析 KEY=VALUE 格式
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();
                    
                    // 移除引号（如果存在）
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    
                    if (!key.isEmpty()) {
                        properties.put(key, value);
                        log.debug("加载环境变量: {} = {}", key, value.contains("key") || value.contains("secret") ? "***" : value);
                    }
                } else {
                    log.warn(".env 文件第 {} 行格式错误: {}", lineNumber, line);
                }
            }
        } catch (IOException e) {
            log.error("读取 .env 文件失败: {}", e.getMessage(), e);
        }
        
        return properties;
    }
}

