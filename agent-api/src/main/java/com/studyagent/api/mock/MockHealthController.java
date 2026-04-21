package com.studyagent.api.mock;

import com.studyagent.api.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MockHealthController {

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "healthy");
        data.put("app_name", "StudyAgent Backend Mock");
        data.put("environment", "mock");
        data.put("database", "disabled");
        data.put("mode", "memory");
        return Result.success(data);
    }
}
