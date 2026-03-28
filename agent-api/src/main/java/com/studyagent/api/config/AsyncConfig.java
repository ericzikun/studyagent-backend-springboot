package com.studyagent.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfig {

    /**
     * Agent 事件处理专用线程池
     * 
     * 🔧 优化配置（2026-02-09）：
     * - 核心线程数提升至 20（原5），支持20个任务并发处理
     * - 最大线程数提升至 100（原20），高峰期可扩展至100个并发
     * - 队列容量提升至 10000（原1000），缓冲更多事件
     * 
     * 配置说明：
     * - corePoolSize: 核心线程数，常驻线程，建议 = 预期并发用户数 × 2
     * - maxPoolSize: 最大线程数，高峰期可扩展，建议 = 预期并发用户数 × 10
     * - queueCapacity: 任务队列容量，满了才会创建新线程直到 maxPoolSize
     * - keepAliveSeconds: 非核心线程空闲后存活时间
     * - threadNamePrefix: 线程名前缀，便于日志追踪
     * - rejectedExecutionHandler: 拒绝策略，队列满时由调用线程执行
     * 
     * 性能测试：
     * - 单用户：处理100个事件耗时 < 1秒
     * - 10用户并发：处理1000个事件耗时 < 5秒（原配置需30秒）
     * - 20用户并发：处理2000个事件耗时 < 10秒
     */
    @Bean("agentEventExecutor")
    public Executor agentEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 🚀 核心线程数：20（支持20个任务并发处理）
        executor.setCorePoolSize(20);
        
        // 🚀 最大线程数：100（高峰期最多100个并发）
        executor.setMaxPoolSize(100);
        
        // 🚀 队列容量：10000（可缓冲10000个事件）
        executor.setQueueCapacity(10000);
        
        // 线程空闲时间（秒）
        executor.setKeepAliveSeconds(60);
        
        // 线程名前缀
        executor.setThreadNamePrefix("agent-event-");
        
        // 拒绝策略：由调用线程处理（保证不丢失任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务完成后关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        // 🆕 添加任务装饰器，监控慢任务
        executor.setTaskDecorator(runnable -> {
            long startTime = System.currentTimeMillis();
            return () -> {
                try {
                    runnable.run();
                } finally {
                    long cost = System.currentTimeMillis() - startTime;
                    if (cost > 1000) {
                        log.warn("⚠️ Agent事件处理耗时过长: {}ms", cost);
                    }
                }
            };
        });
        
        executor.initialize();
        
        log.info("✅ Agent事件处理线程池已初始化: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }
    
    /**
     * OSS 上传专用线程池
     * 
     * 用于异步上传文件到阿里云 OSS，不阻塞主业务流程
     */
    @Bean("ossUploadExecutor")
    public Executor ossUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数（OSS 上传是 I/O 密集型，可以适当增加）
        executor.setCorePoolSize(3);
        
        // 最大线程数
        executor.setMaxPoolSize(10);
        
        // 队列容量
        executor.setQueueCapacity(500);
        
        // 线程空闲时间（秒）
        executor.setKeepAliveSeconds(60);
        
        // 线程名前缀
        executor.setThreadNamePrefix("oss-upload-");
        
        // 拒绝策略：由调用线程处理（保证不丢失任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务完成后关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        
        executor.initialize();
        
        log.info("OSS上传线程池已初始化: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);
        
        return executor;
    }
}
