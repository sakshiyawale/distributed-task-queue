package com.taskqueue.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.Task;
import com.taskqueue.service.TaskService;
import com.taskqueue.worker.processors.GenericTaskProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TaskWorker {

    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    // Strategy pattern: keyed by task type, built from all @Component TaskProcessor beans
    private final Map<String, TaskProcessor> processors;

    // Single-thread scheduler used to enforce exponential backoff delay before re-enqueueing retries
    private final ScheduledExecutorService retryScheduler = new ScheduledThreadPoolExecutor(1);

    private static final String RETRY_TOPIC = "task-retry";

    private String workerId;

    public TaskWorker(TaskService taskService,
                      ObjectMapper objectMapper,
                      KafkaTemplate<String, String> kafkaTemplate,
                      SimpMessagingTemplate messagingTemplate,
                      List<TaskProcessor> processorList) {
        this.taskService      = taskService;
        this.objectMapper     = objectMapper;
        this.kafkaTemplate    = kafkaTemplate;
        this.messagingTemplate = messagingTemplate;
        // Build the strategy registry from all @Component TaskProcessor beans
        this.processors = processorList.stream()
                .collect(Collectors.toMap(TaskProcessor::getType, p -> p));
    }

    @PostConstruct
    public void init() {
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        taskService.registerWorker(workerId, "ACTIVE");
        log.info("Worker initialized: {} | registered processors: {}", workerId, processors.keySet());
    }

    /**
     * Kafka consumer threads call this method then immediately return to poll the next message.
     * Actual task execution is offloaded to the "taskExecutor" thread pool via @Async.
     * Manual ack is committed after the task is accepted for async execution.
     */
    @KafkaListener(topics = {"task-queue-low", "task-queue-normal", "task-queue-high", "task-queue-urgent"})
    public void processTask(String taskJson, Acknowledgment ack) {
        handleIncoming(taskJson, ack, false);
    }

    @KafkaListener(topics = RETRY_TOPIC)
    public void processRetryTask(String taskJson, Acknowledgment ack) {
        handleIncoming(taskJson, ack, true);
    }

    private void handleIncoming(String taskJson, Acknowledgment ack, boolean isRetry) {
        try {
            Task task = objectMapper.readValue(taskJson, Task.class);

            // Idempotency check: re-fetch latest state from Redis before executing.
            // Prevents duplicate execution if Kafka redelivers a message (e.g. after rebalance).
            Task latest = taskService.getTask(task.getId());
            if (latest != null) {
                Task.TaskStatus s = latest.getStatus();
                if (s == Task.TaskStatus.COMPLETED || s == Task.TaskStatus.CANCELLED) {
                    log.info("Skipping already-{} task: {}", s, task.getId());
                    ack.acknowledge();
                    return;
                }
                // Use the latest Redis state to pick up any manual pause/cancel
                task = latest;
            }

            if (isRetry) {
                log.info("Retrying task: {} (attempt {})", task.getId(), task.getRetryCount() + 1);
            }

            // Acknowledge offset now — execution is handed off to async thread pool.
            // If the app crashes after ack but before completion, the task stays PROCESSING
            // in Redis and can be recovered by a stuck-task scanner (future improvement).
            ack.acknowledge();
            executeTaskAsync(task);

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize task message", e);
            ack.acknowledge(); // Don't block the partition on a malformed message
        }
    }

    /**
     * Runs in the "taskExecutor" thread pool (configured in TaskQueueApplication).
     * Kafka consumer threads are never blocked by task execution.
     */
    @Async("taskExecutor")
    public void executeTaskAsync(Task task) {
        long startTime = System.currentTimeMillis();

        try {
            task.setStatus(Task.TaskStatus.PROCESSING);
            task.setWorkerId(workerId);
            task.setStartedAt(LocalDateTime.now());
            taskService.updateTask(task);
            sendTaskUpdate(task);
            taskService.registerWorker(workerId, "BUSY");

            // Strategy pattern: look up processor by task type, fall back to GENERIC
            TaskProcessor processor = processors.getOrDefault(task.getType(),
                    processors.get(GenericTaskProcessor.GENERIC_TYPE));
            String result = processor.process(task.getPayload());

            task.setStatus(Task.TaskStatus.COMPLETED);
            task.setResult(result);
            task.setCompletedAt(LocalDateTime.now());
            task.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            taskService.updateTask(task);
            log.info("Task completed: {} in {}ms", task.getId(), task.getExecutionTimeMs());

        } catch (Exception e) {
            log.error("Task failed: {}", task.getId(), e);
            handleTaskFailure(task, e, startTime);
        } finally {
            taskService.registerWorker(workerId, "ACTIVE");
            sendTaskUpdate(task);
        }
    }

    private void handleTaskFailure(Task task, Exception e, long startTime) {
        task.setError(e.getMessage());
        task.setExecutionTimeMs(System.currentTimeMillis() - startTime);

        if (task.getRetryCount() < task.getMaxRetries()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(Task.TaskStatus.RETRYING);
            taskService.updateTask(task);

            // Enforce exponential backoff delay before re-enqueueing.
            // Previously the delay was calculated but the send was immediate.
            long delaySeconds = (long) Math.pow(2, task.getRetryCount()); // 2, 4, 8 seconds
            log.info("Task {} scheduled for retry in {}s (attempt {})",
                    task.getId(), delaySeconds, task.getRetryCount());

            final Task taskToRetry = task;
            retryScheduler.schedule(() -> {
                try {
                    String taskJson = objectMapper.writeValueAsString(taskToRetry);
                    kafkaTemplate.send(RETRY_TOPIC, taskToRetry.getId(), taskJson);
                } catch (JsonProcessingException ex) {
                    log.error("Failed to enqueue retry for task: {}", taskToRetry.getId(), ex);
                }
            }, delaySeconds, TimeUnit.SECONDS);

        } else {
            task.setStatus(Task.TaskStatus.FAILED);
            taskService.updateTask(task);
            log.error("Task failed permanently after {} attempts: {}", task.getMaxRetries(), task.getId());
        }
    }

    private void sendTaskUpdate(Task task) {
        try {
            messagingTemplate.convertAndSend("/topic/task-updates", task);
        } catch (Exception e) {
            log.error("Failed to send WebSocket task update for: {}", task.getId(), e);
        }
    }
}
