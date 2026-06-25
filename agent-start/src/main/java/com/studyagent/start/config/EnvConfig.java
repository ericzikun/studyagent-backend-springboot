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
import java.util.List;
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

        List<File> envFiles = findEnvFiles();
        if (!envFiles.isEmpty()) {
            Map<String, Object> envProperties = loadEnvFiles(envFiles);

            if (!envProperties.isEmpty()) {
                MapPropertySource propertySource = new MapPropertySource("envFile", envProperties);
                environment.getPropertySources().addFirst(propertySource);
                log.info("已加载 {} 个环境变量从本地 env 文件", envProperties.size());
            }
        } else {
            log.debug("未找到 .env 或 .env.local 文件，跳过加载");
        }
    }

    /**
     * 查找本地 env 文件。
     *
     * `./start-mock.sh` 会从 agent-start 目录启动 Spring Boot，所以需要先看
     * 当前目录，再看父目录；`.env.local` 后加载，用于覆盖共享 `.env`。
     */
    List<File> findEnvFiles() {
        List<File> currentDirFiles = existingEnvFiles(".");
        if (!currentDirFiles.isEmpty()) {
            return currentDirFiles;
        }
        return existingEnvFiles("..");
    }

    private List<File> existingEnvFiles(String baseDir) {
        return java.util.stream.Stream.of(".env", ".env.local")
                .map(fileName -> new File(baseDir, fileName))
                .filter(file -> file.exists() && file.isFile())
                .toList();
    }

    Map<String, Object> loadEnvFiles(List<File> envFiles) {
        Map<String, Object> properties = new HashMap<>();
        for (File envFile : envFiles) {
            log.info("找到 env 文件: {}", envFile.getAbsolutePath());
            properties.putAll(loadEnvFile(envFile));
        }
        return properties;
    }
    
    /**
     * 加载 .env 文件内容。
     *
     * 支持 shell 风格的 `export KEY=VALUE`，让本地 `./start-mock.sh`
     * 直接启动时也能读取同一份 .env 配置。
     */
    Map<String, Object> loadEnvFile(File envFile) {
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

                line = stripOptionalExportPrefix(line);
                
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
                        log.debug("加载环境变量: {} = {}", key, maskIfSensitive(key, value));
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

    private String stripOptionalExportPrefix(String line) {
        if (line.startsWith("export ")) {
            return line.substring("export ".length()).trim();
        }
        if (line.startsWith("export\t")) {
            return line.substring("export".length()).trim();
        }
        return line;
    }

    private String maskIfSensitive(String key, String value) {
        String normalizedKey = key == null ? "" : key.toLowerCase();
        if (normalizedKey.contains("key")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("token")
                || normalizedKey.contains("password")) {
            return "***";
        }
        return value;
    }
}
