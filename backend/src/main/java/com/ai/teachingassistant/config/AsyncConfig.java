package com.ai.teachingassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables Spring's @Async support for background summarization tasks.
 *
 * When a user selects "Ask Questions" or "Quiz" mode, the PDF is indexed into
 * pgvector immediately (for low-latency RAG Q&A), while the AI summarization
 * runs in this background thread pool — so the user is never blocked waiting
 * for the LLM.
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "summarizationExecutor")
    public Executor summarizationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("async-summ-");
        executor.initialize();
        return executor;
    }
}
