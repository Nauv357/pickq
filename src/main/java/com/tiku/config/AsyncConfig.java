package com.tiku.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 导入任务执行器：单线程串行（避免并发触发模型限流）。
 * aiChunkExecutor：分块并行的 AI 调用池（单任务内最多 3 路并发，全局同样受限）。
 */
@Configuration
public class AsyncConfig {

    @Bean("aiImportExecutor")
    public Executor aiImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ai-import-");
        executor.initialize();
        return executor;
    }

    @Bean("aiChunkExecutor")
    public ExecutorService aiChunkExecutor() {
        //daemon 线程：应用退出不等待分块调用
        return Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "ai-chunk-");
            t.setDaemon(true);
            return t;
        });
    }
}
