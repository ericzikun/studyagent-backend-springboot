package com.studyagent.start.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger配置
 */
@Configuration
public class SwaggerConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("StudyAgent Backend API")
                .version("1.0.0")
                .description("StudyAgent 后端 RESTful API 文档")
                .contact(new Contact()
                    .name("StudyAgent Team")
                    .email("support@studyagent.com")
                )
            );
    }
}

