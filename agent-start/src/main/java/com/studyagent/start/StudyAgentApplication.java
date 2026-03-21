package com.studyagent.start;

import com.studyagent.start.config.EnvConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StudyAgent 后端应用启动类
 */
@SpringBootApplication(scanBasePackages = {
    "com.studyagent.common",
    "com.studyagent.api",
    "com.studyagent.service",
    "com.studyagent.infra",
    "com.studyagent.start"
})
@MapperScan("com.studyagent.infra.mapper")
@EnableScheduling
public class StudyAgentApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(StudyAgentApplication.class);
        // 添加 .env 文件加载监听器
        app.addListeners(new EnvConfig());
        app.run(args);
    }
}

