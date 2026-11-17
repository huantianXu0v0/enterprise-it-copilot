package com.xht.itcopilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RagExecutorConfig {

    @Bean(name = "ragStreamHeartbeat", destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService ragStreamHeartbeat() {
        var scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rag-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean("ragChatExecutor")
    public ThreadPoolTaskExecutor ragChatExecutor(
            @Value("${rag.chat-executor.core-size:4}") int coreSize,
            @Value("${rag.chat-executor.max-size:8}") int maxSize,
            @Value("${rag.chat-executor.queue-capacity:8}") int queueCapacity,
            @Value("${rag.chat-executor.keep-alive-seconds:60}") int keepAliveSeconds) {

        Assert.isTrue(coreSize > 0, "core-size 必须大于0");
        Assert.isTrue(maxSize >= coreSize,
                "max-size 不能小于 core-size");
        Assert.isTrue(queueCapacity >= 0,
                "queue-capacity 不能小于0");
        Assert.isTrue(keepAliveSeconds >= 0,
                "keep-alive-seconds 不能小于0");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);

        // 日志中方便识别处理问答的线程。
        executor.setThreadNamePrefix("rag-chat-");

        // 线程和队列都满时抛出异常，由接口返回“服务繁忙”。
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());

        // 容器开始关闭时，提前拒绝新任务。
        executor.setStrictEarlyShutdown(true);

        // 销毁线程池时，最多等待线程结束30秒。
        executor.setAwaitTerminationSeconds(30);

        // 初始化和销毁交给 Spring 管理。
        return executor;
    }
}