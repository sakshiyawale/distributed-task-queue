package com.taskqueue.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String TASK_TOPIC = "task-queue";
    private static final String TASK_PREFIX = "task:";
    private static final String TASK_LIST = "tasks:all";
    private static final String WORKER_PREFIX = "worker:";

    public Task submitTask(String type, Map<String, Object> payload, Task.Priority priority) {
        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setType(type);
        task.setStatus(Task.TaskStatus.PENDING);
        task.setPayload(payload);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setPriority(priority != null ? priority : Task.Priority.NORMAL);
        task.setCreatedAt(LocalDateTime.now());
        
        try {
            // Store task in Redis
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getId(), taskJson, 24, TimeUnit.HOURS);
            redisTemplate.opsForList().leftPush(TASK_LIST, task.getId());
            
            // Send to Kafka topic based on priority
            String topicName = TASK_TOPIC + "-" + task.getPriority().name().toLowerCase();
            kafkaTemplate.send(topicName, task.getId(), taskJson);
            
            log.info("Task submitted: {} with priority {}", task.getId(), task.getPriority());
            return task;
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize task: {}", task.getId(), e);
            throw new RuntimeException("Failed to submit task", e);
        }
    }

    public Task getTask(String taskId) {
        String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
        if (taskJson != null) {
            try {
                return objectMapper.readValue(taskJson, Task.class);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize task: {}", taskId, e);
            }
        }
        return null;
    }

    public void updateTask(Task task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getId(), taskJson, 24, TimeUnit.HOURS);
            log.debug("Task updated: {}", task.getId());
        } catch (JsonProcessingException e) {
            log.error("Failed to update task: {}", task.getId(), e);
        }
    }

    public List<Task> getAllTasks() {
        List<String> taskIds = redisTemplate.opsForList().range(TASK_LIST, 0, -1);
        if (taskIds == null) return new ArrayList<>();
        
        return taskIds.stream()
                .map(this::getTask)
                .filter(Objects::nonNull)
                .sorted((t1, t2) -> t2.getCreatedAt().compareTo(t1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    public List<Task> getTasksByStatus(Task.TaskStatus status) {
        return getAllTasks().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getTaskStatistics() {
        List<Task> allTasks = getAllTasks();
        Map<String, Object> stats = new HashMap<>();

        long total = allTasks.size();
        long completed = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED).count();
        long failed = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.FAILED).count();
        long pending = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.PENDING).count();
        long running = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.PROCESSING || t.getStatus() == Task.TaskStatus.RETRYING).count();
        long cancelled = 0L;

        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("failed", failed);
        stats.put("pending", pending);
        stats.put("running", running);
        stats.put("cancelled", cancelled);

        double avgExecutionTime = allTasks.stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .mapToLong(t -> t.getExecutionTimeMs())
                .average()
                .orElse(0.0);
        stats.put("avgExecutionTimeMs", avgExecutionTime);

        return stats;
    }

    public void registerWorker(String workerId, String status) {
        Map<String, Object> workerInfo = new HashMap<>();
        workerInfo.put("id", workerId);
        workerInfo.put("status", status);
        workerInfo.put("lastSeen", LocalDateTime.now().toString());
        
        try {
            String workerJson = objectMapper.writeValueAsString(workerInfo);
            redisTemplate.opsForValue().set(WORKER_PREFIX + workerId, workerJson, 5, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("Failed to register worker: {}", workerId, e);
        }
    }

    public List<Map<String, Object>> getActiveWorkers() {
        Set<String> keys = redisTemplate.keys(WORKER_PREFIX + "*");
        if (keys == null) return new ArrayList<>();
        
        return keys.stream()
                .map(key -> redisTemplate.opsForValue().get(key))
                .filter(Objects::nonNull)
                .map(json -> {
                    try {
                        //noinspection unchecked
                        return (Map<String, Object>) objectMapper.readValue(json, Map.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize worker info", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean cancelTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        if (task.getStatus() == Task.TaskStatus.PENDING || task.getStatus() == Task.TaskStatus.PROCESSING || task.getStatus() == Task.TaskStatus.RETRYING) {
            task.setStatus(Task.TaskStatus.FAILED); // Or create a CANCELLED status if you want
            updateTask(task);
            return true;
        }
        return false;
    }

    public boolean pauseTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        if (task.getStatus() == Task.TaskStatus.PENDING || task.getStatus() == Task.TaskStatus.PROCESSING) {
            task.setStatus(Task.TaskStatus.PAUSED);
            updateTask(task);
            return true;
        }
        return false;
    }

    public boolean retryTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        if (task.getStatus() == Task.TaskStatus.FAILED) {
            task.setStatus(Task.TaskStatus.PENDING);
            task.setRetryCount(0);
            updateTask(task);
            // Optionally, re-enqueue the task to Kafka here
            return true;
        }
        return false;
    }

    public boolean deleteTask(String taskId) {
        List<String> taskIds = redisTemplate.opsForList().range(TASK_LIST, 0, -1);
        if (taskIds == null || !taskIds.contains(taskId)) return false;
        redisTemplate.delete(TASK_PREFIX + taskId);
        redisTemplate.opsForList().remove(TASK_LIST, 0, taskId);
        return true;
    }
}