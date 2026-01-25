package com.studyagent.infra.client.python;

import com.studyagent.service.domain.task.PythonBackendClient;
import com.studyagent.service.domain.task.TaskId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python后端客户端实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonBackendClientImpl implements PythonBackendClient {
    
    private final WebClient webClient;
    
    @Value("${python-backend.url:http://localhost:8000}")
    private String pythonBackendUrl;
    
    @Override
    public void executeTask(TaskId taskId) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("task_id", taskId.getValue());
            
            webClient.post()
                .uri(pythonBackendUrl + "/v1/task/execute")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
            log.info("Successfully called Python backend to execute task: {}", taskId.getValue());
        } catch (Exception e) {
            log.error("Failed to call Python backend for task execution: {}", taskId.getValue(), e);
            throw new RuntimeException("Failed to execute task in Python backend", e);
        }
    }
    
    @Override
    public ClarifyTaskResult clarifyTask(Map<String, Object> request) {
        try {
            log.info("Calling Python backend to clarify task: {}", request);
            
            Map<String, Object> response = webClient.post()
                .uri(pythonBackendUrl + "/v1/task/clarify")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), 
                    clientResponse -> {
                        log.error("Python backend returned error status: {}", clientResponse.statusCode());
                        return Mono.error(new RuntimeException("Python backend returned error: " + clientResponse.statusCode()));
                    })
                .bodyToMono(Map.class)
                .block();
            
            if (response == null) {
                log.warn("Python backend returned null response for clarify task");
                return new ClarifyTaskResult(List.of(), "无法生成追问问题，请稍后重试");
            }
            
            // 解析响应
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) response.get("meta");
            if (meta != null) {
                Integer statusCode = (Integer) meta.get("statusCode");
                if (statusCode != null && statusCode != 0) {
                    String statusMsg = (String) meta.get("statusMsg");
                    log.warn("Python backend returned error: statusCode={}, statusMsg={}", statusCode, statusMsg);
                    return new ClarifyTaskResult(List.of(), statusMsg != null ? statusMsg : "生成追问问题失败");
                }
            }
            
            @SuppressWarnings("unchecked")
            List<String> questions = (List<String>) response.get("questions");
            String suggestions = (String) response.get("suggestions");
            
            if (questions == null) {
                questions = List.of();
            }
            
            log.info("Successfully received clarifying questions from Python backend: {} questions", questions.size());
            return new ClarifyTaskResult(questions, suggestions);
            
        } catch (Exception e) {
            log.error("Failed to call Python backend for clarify task", e);
            return new ClarifyTaskResult(List.of(), "调用追问服务失败，请稍后重试");
        }
    }
}

