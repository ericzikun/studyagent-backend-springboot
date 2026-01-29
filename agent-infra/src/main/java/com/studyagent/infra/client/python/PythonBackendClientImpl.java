package com.studyagent.infra.client.python;

import com.studyagent.common.log.annotation.ExternalLog;
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
import java.util.stream.Collectors;

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
    @ExternalLog(service = "Python后端", api = "执行任务")
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
    @ExternalLog(service = "Python后端", api = "停止任务")
    public void stopTask(TaskId taskId) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("task_id", taskId.getValue());

            webClient.post()
                .uri(pythonBackendUrl + "/v1/task/stop")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class)
                .block();

            log.info("Successfully called Python backend to stop task: {}", taskId.getValue());
        } catch (Exception e) {
            log.error("Failed to call Python backend to stop task: {}", taskId.getValue(), e);
            throw new RuntimeException("Failed to stop task in Python backend", e);
        }
    }
    
    @Override
    @ExternalLog(service = "Python后端", api = "追问任务", slowThreshold = 10000)
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

    @Override
    @ExternalLog(service = "Python后端", api = "获取任务队列信息", ignoreException = true)
    public TaskQueueInfo getTaskQueueInfo(TaskId taskId) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("task_id", taskId.getValue());

            Map<String, Object> response = webClient.post()
                .uri(pythonBackendUrl + "/v1/task/queue")
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
                log.warn("Python backend returned null response for queue info");
                return new TaskQueueInfo(0, false);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) response.get("meta");
            Integer statusCode = null;
            if (meta != null) {
                Object statusCodeRaw = meta.get("status_code");
                if (statusCodeRaw == null) {
                    statusCodeRaw = meta.get("statusCode");
                }
                if (statusCodeRaw instanceof Number) {
                    statusCode = ((Number) statusCodeRaw).intValue();
                }
            }
            if (statusCode != null && statusCode != 0) {
                log.warn("Python backend returned error for queue info: statusCode={}", statusCode);
                return new TaskQueueInfo(0, false);
            }

            Object aheadCountRaw = response.get("ahead_count");
            if (aheadCountRaw == null) {
                aheadCountRaw = response.get("aheadCount");
            }
            int aheadCount = 0;
            if (aheadCountRaw instanceof Number) {
                aheadCount = ((Number) aheadCountRaw).intValue();
            }

            Object isRunningRaw = response.get("is_running");
            if (isRunningRaw == null) {
                isRunningRaw = response.get("isRunning");
            }
            boolean isRunning = isRunningRaw instanceof Boolean && (Boolean) isRunningRaw;

            return new TaskQueueInfo(aheadCount, isRunning);
        } catch (Exception e) {
            log.error("Failed to call Python backend for queue info: {}", taskId.getValue(), e);
            return new TaskQueueInfo(0, false);
        }
    }

    @Override
    @ExternalLog(service = "Python后端", api = "批量获取任务队列信息", ignoreException = true)
    public Map<Long, TaskQueueInfo> getTaskQueueBatchInfo(List<TaskId> taskIds) {
        try {
            List<Long> ids = taskIds.stream().map(TaskId::getValue).collect(Collectors.toList());
            Map<String, Object> request = new HashMap<>();
            request.put("task_ids", ids);

            Map<String, Object> response = webClient.post()
                .uri(pythonBackendUrl + "/v1/task/queue/batch")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> {
                        log.error("Python backend returned error status: {}", clientResponse.statusCode());
                        return Mono.error(new RuntimeException("Python backend returned error: " + clientResponse.statusCode()));
                    })
                .bodyToMono(Map.class)
                .block();

            Map<Long, TaskQueueInfo> result = new HashMap<>();
            if (response == null) {
                log.warn("Python backend returned null response for batch queue info");
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) response.get("meta");
            Integer statusCode = null;
            if (meta != null) {
                Object statusCodeRaw = meta.get("status_code");
                if (statusCodeRaw == null) {
                    statusCodeRaw = meta.get("statusCode");
                }
                if (statusCodeRaw instanceof Number) {
                    statusCode = ((Number) statusCodeRaw).intValue();
                }
            }
            if (statusCode != null && statusCode != 0) {
                log.warn("Python backend returned error for batch queue info: statusCode={}", statusCode);
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> results = (Map<String, Object>) response.get("results");
            if (results == null) {
                return result;
            }

            for (Map.Entry<String, Object> entry : results.entrySet()) {
                Long taskId = null;
                try {
                    taskId = Long.valueOf(entry.getKey());
                } catch (Exception ignored) {
                }
                if (taskId == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = entry.getValue() instanceof Map
                    ? (Map<String, Object>) entry.getValue()
                    : null;
                if (item == null) {
                    continue;
                }
                Object aheadCountRaw = item.get("ahead_count");
                if (aheadCountRaw == null) {
                    aheadCountRaw = item.get("aheadCount");
                }
                int aheadCount = 0;
                if (aheadCountRaw instanceof Number) {
                    aheadCount = ((Number) aheadCountRaw).intValue();
                }

                Object isRunningRaw = item.get("is_running");
                if (isRunningRaw == null) {
                    isRunningRaw = item.get("isRunning");
                }
                boolean isRunning = isRunningRaw instanceof Boolean && (Boolean) isRunningRaw;

                result.put(taskId, new TaskQueueInfo(aheadCount, isRunning));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to call Python backend for batch queue info", e);
            return new HashMap<>();
        }
    }
}

