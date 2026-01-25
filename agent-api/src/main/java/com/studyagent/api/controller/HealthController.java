package com.studyagent.api.controller;

import com.studyagent.api.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 */
@Slf4j
@RestController
public class HealthController {
    
    @Autowired(required = false)
    private DataSource dataSource;
    
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "healthy");
        data.put("app_name", "StudyAgent Backend");
        data.put("environment", "development");
        
        // 检查数据库连接
        String dbStatus = "unknown";
        try {
            if (dataSource != null) {
                try (Connection conn = dataSource.getConnection()) {
                    if (conn != null && !conn.isClosed()) {
                        dbStatus = "connected";
                    }
                }
            } else {
                dbStatus = "not_configured";
            }
        } catch (Exception e) {
            log.warn("数据库连接检查失败: {}", e.getMessage());
            dbStatus = "disconnected";
        }
        
        data.put("database", dbStatus);
        return Result.success(data);
    }
}

