package com.taskqueue.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.Task;
import com.taskqueue.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskWorker {
    
    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    
    private String workerId;
    private static final String RETRY_TOPIC = "task-retry";

    @PostConstruct
    public void init() {
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        taskService.registerWorker(workerId, "ACTIVE");
        log.info("Worker initialized: {}", workerId);
    }

    @KafkaListener(topics = {"task-queue-low", "task-queue-normal", "task-queue-high", "task-queue-urgent"})
    public void processTask(String taskJson) {
        try {
            Task task = objectMapper.readValue(taskJson, Task.class);
            executeTask(task);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize task", e);
        }
    }

    @KafkaListener(topics = RETRY_TOPIC)
    public void processRetryTask(String taskJson) {
        try {
            Task task = objectMapper.readValue(taskJson, Task.class);
            log.info("Retrying task: {} (attempt {})", task.getId(), task.getRetryCount() + 1);
            executeTask(task);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize retry task", e);
        }
    }

    private void executeTask(Task task) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Update task status to processing
            task.setStatus(Task.TaskStatus.PROCESSING);
            task.setWorkerId(workerId);
            task.setStartedAt(LocalDateTime.now());
            taskService.updateTask(task);
            
            // Send real-time update
            sendTaskUpdate(task);
            
            // Register worker as busy
            taskService.registerWorker(workerId, "BUSY");
            
            // Simulate task processing based on task type
            Object result = processTaskByType(task);
            
            // Task completed successfully
            task.setStatus(Task.TaskStatus.COMPLETED);
            task.setResult(result.toString());
            task.setCompletedAt(LocalDateTime.now());
            task.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            
            taskService.updateTask(task);
            log.info("Task completed: {} in {}ms", task.getId(), task.getExecutionTimeMs());
            
        } catch (Exception e) {
            log.error("Task failed: {}", task.getId(), e);
            handleTaskFailure(task, e, startTime);
        } finally {
            // Register worker as available
            taskService.registerWorker(workerId, "ACTIVE");
            sendTaskUpdate(task);
        }
    }

    private Object processTaskByType(Task task) throws Exception {
        Map<String, Object> payload = task.getPayload();
        
        switch (task.getType()) {
            case "EMAIL_SEND":
                return processEmailTask(payload);
            case "IMAGE_PROCESS":
                return processImageTask(payload);
            case "DATA_EXPORT":
                return processDataExportTask(payload);
            case "REPORT_GENERATE":
                return processReportTask(payload);
            default:
                return processGenericTask(payload);
        }
    }

    private Object processEmailTask(Map<String, Object> payload) throws InterruptedException {
        // Simulate email sending
        String recipient = (String) payload.get("recipient");
        String subject = (String) payload.get("subject");
        
        Thread.sleep(2000); // Simulate email sending delay
        
        return "Email sent to " + recipient + " with subject: " + subject;
    }

    private Object processImageTask(Map<String, Object> payload) throws InterruptedException {
        // Simulate image processing
        String imageUrl = (String) payload.get("imageUrl");
        String operation = (String) payload.get("operation");
        
        Thread.sleep(5000); // Simulate image processing delay
        
        return "Image processed: " + imageUrl + " with operation: " + operation;
    }

    private Object processDataExportTask(Map<String, Object> payload) throws InterruptedException {
        // Simulate data export
        String format = (String) payload.get("format");
        Integer recordCount = (Integer) payload.get("recordCount");
        
        Thread.sleep(3000); // Simulate data export delay
        
        return "Exported " + recordCount + " records in " + format + " format";
    }

    private Object processReportTask(Map<String, Object> payload) throws InterruptedException {
        // Simulate report generation
        String reportType = (String) payload.get("reportType");
        String dateRange = (String) payload.get("dateRange");
        
        Thread.sleep(8000); // Simulate report generation delay
        
        return "Generated " + reportType + " report for " + dateRange;
    }

    private Object processGenericTask(Map<String, Object> payload) throws InterruptedException {
        // Simulate generic task processing
        Thread.sleep(1000);
        return "Generic task processed with payload: " + payload.toString();
    }

    private void handleTaskFailure(Task task, Exception e, long startTime) {
        task.setError(e.getMessage());
        task.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        
        if (task.getRetryCount() < task.getMaxRetries()) {
            // Retry the task
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(Task.TaskStatus.RETRYING);
            taskService.updateTask(task);
            
            // Send to retry topic with exponential backoff
            try {
                String taskJson = objectMapper.writeValueAsString(task);
                long delay = (long) Math.pow(2, task.getRetryCount()) * 1000; // Exponential backoff
                
                // In a real implementation, you'd use a delay queue or scheduled task
                // For demo purposes, we'll just send to retry topic immediately
                kafkaTemplate.send(RETRY_TOPIC, task.getId(), taskJson);
                
                log.info("Task scheduled for retry: {} (attempt {})", task.getId(), task.getRetryCount());
            } catch (JsonProcessingException ex) {
                log.error("Failed to schedule task retry: {}", task.getId(), ex);
            }
        } else {
            // Max retries reached, mark as failed
            task.setStatus(Task.TaskStatus.FAILED);
            taskService.updateTask(task);
            log.error("Task failed permanently: {}", task.getId());
        }
    }

    private void sendTaskUpdate(Task task) {
        try {
            // Send real-time update to WebSocket subscribers
            messagingTemplate.convertAndSend("/topic/task-updates", task);
        } catch (Exception e) {
            log.error("Failed to send task update", e);
        }
    }
}