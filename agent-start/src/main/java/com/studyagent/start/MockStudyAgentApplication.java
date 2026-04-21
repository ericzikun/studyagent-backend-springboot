package com.studyagent.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mock API 启动入口。
 *
 * 仅用于前端联调，不依赖数据库、Clerk、Python 后端。
 */
@EnableScheduling
@SpringBootApplication(
    scanBasePackages = {
        "com.studyagent.common",
        "com.studyagent.api.common",
        "com.studyagent.api.mock",
        "com.studyagent.start.config"
    },
    exclude = {
        DataSourceAutoConfiguration.class
    }
)
public class MockStudyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockStudyAgentApplication.class, args);
    }
}
