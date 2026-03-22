package com.taskqueue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;

import java.util.concurrent.Executor;

@SpringBootApplication
@EnableKafka
@EnableAsync
@EnableScheduling
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class TaskQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskQueueApplication.class, args);
    }

    /**
     * Dedicated thread pool for async task execution in TaskWorker.
     * Keeps Kafka consumer threads free to immediately pull the next message.
     */
    @Bean("taskExecutor")
    public Executor taskExecutor(
            @Value("${task.worker.core-pool-size:5}") int corePoolSize,
            @Value("${task.worker.max-pool-size:20}") int maxPoolSize,
            @Value("${task.worker.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("task-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
