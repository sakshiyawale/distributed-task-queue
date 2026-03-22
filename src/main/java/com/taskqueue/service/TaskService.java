package com.taskqueue.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
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

    private static final String TASK_TOPIC   = "task-queue";
    private static final String TASK_PREFIX  = "task:";
    private static final String TASK_LIST    = "tasks:all";
    private static final String WORKER_PREFIX = "worker:";

    // ── Submit ────────────────────────────────────────────────────────────────

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
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getId(), taskJson, 24, TimeUnit.HOURS);
            redisTemplate.opsForList().leftPush(TASK_LIST, task.getId());

            String topicName = TASK_TOPIC + "-" + task.getPriority().name().toLowerCase();
            kafkaTemplate.send(topicName, task.getId(), taskJson);

            log.info("Task submitted: {} priority={}", task.getId(), task.getPriority());
            return task;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize task: {}", task.getId(), e);
            throw new RuntimeException("Failed to submit task", e);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

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

    /**
     * Paginated task fetch — uses LRANGE with offset/limit for O(1) Redis access.
     * Avoids loading the entire task list into memory on every request.
     */
    public List<Task> getAllTasks(int page, int size) {
        long start = (long) page * size;
        long end   = start + size - 1;
        List<String> taskIds = redisTemplate.opsForList().range(TASK_LIST, start, end);
        if (taskIds == null) return Collections.emptyList();
        return taskIds.stream()
                .map(this::getTask)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** Unpaginated overload — used internally for statistics. */
    public List<Task> getAllTasks() {
        List<String> taskIds = redisTemplate.opsForList().range(TASK_LIST, 0, -1);
        if (taskIds == null) return Collections.emptyList();
        return taskIds.stream()
                .map(this::getTask)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<Task> getTasksByStatus(Task.TaskStatus status) {
        return getAllTasks().stream()
                .filter(task -> task.getStatus() == status)
                .collect(Collectors.toList());
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public void updateTask(Task task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getId(), taskJson, 24, TimeUnit.HOURS);
            log.debug("Task updated: {} status={}", task.getId(), task.getStatus());
        } catch (JsonProcessingException e) {
            log.error("Failed to update task: {}", task.getId(), e);
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    public Map<String, Object> getTaskStatistics() {
        List<Task> allTasks = getAllTasks();

        long completed = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED).count();
        long failed    = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.FAILED).count();
        long pending   = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.PENDING).count();
        long running   = allTasks.stream().filter(t ->
                t.getStatus() == Task.TaskStatus.PROCESSING ||
                t.getStatus() == Task.TaskStatus.RETRYING).count();
        // CANCELLED is now its own status — correctly separated from FAILED
        long cancelled = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.CANCELLED).count();
        long paused    = allTasks.stream().filter(t -> t.getStatus() == Task.TaskStatus.PAUSED).count();

        double avgExecutionTime = allTasks.stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .mapToLong(Task::getExecutionTimeMs)
                .average()
                .orElse(0.0);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total",              allTasks.size());
        stats.put("completed",          completed);
        stats.put("failed",             failed);
        stats.put("pending",            pending);
        stats.put("running",            running);
        stats.put("cancelled",          cancelled);
        stats.put("paused",             paused);
        stats.put("avgExecutionTimeMs", avgExecutionTime);
        return stats;
    }

    // ── Workers ───────────────────────────────────────────────────────────────

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

    /**
     * Uses SCAN instead of KEYS — KEYS blocks Redis on large datasets.
     * SCAN iterates incrementally without holding the server lock.
     */
    public List<Map<String, Object>> getActiveWorkers() {
        List<Map<String, Object>> workers = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(WORKER_PREFIX + "*").count(50).build();

        try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory()
                .getConnection()
                .scan(options)) {
            while (cursor.hasNext()) {
                String key  = new String(cursor.next());
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> worker = objectMapper.readValue(json, Map.class);
                        workers.add(worker);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize worker info for key: {}", key, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan workers", e);
        }
        return workers;
    }

    // ── Task lifecycle actions ────────────────────────────────────────────────

    /**
     * Cancel now uses CANCELLED (distinct from FAILED).
     * FAILED = system error after retries exhausted.
     * CANCELLED = deliberate user action.
     */
    public boolean cancelTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        Task.TaskStatus s = task.getStatus();
        if (s == Task.TaskStatus.PENDING || s == Task.TaskStatus.PROCESSING || s == Task.TaskStatus.RETRYING) {
            task.setStatus(Task.TaskStatus.CANCELLED);
            updateTask(task);
            log.info("Task cancelled by user: {}", taskId);
            return true;
        }
        return false;
    }

    public boolean pauseTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        Task.TaskStatus s = task.getStatus();
        if (s == Task.TaskStatus.PENDING || s == Task.TaskStatus.PROCESSING) {
            task.setStatus(Task.TaskStatus.PAUSED);
            updateTask(task);
            return true;
        }
        return false;
    }

    /**
     * retryTask now re-enqueues to Kafka — previously only updated Redis,
     * so the worker never picked it up again.
     * Also allows retrying CANCELLED tasks, not just FAILED ones.
     */
    public boolean retryTask(String taskId) {
        Task task = getTask(taskId);
        if (task == null) return false;
        Task.TaskStatus s = task.getStatus();
        if (s == Task.TaskStatus.FAILED || s == Task.TaskStatus.CANCELLED) {
            task.setStatus(Task.TaskStatus.PENDING);
            task.setRetryCount(0);
            task.setError(null);
            updateTask(task);

            try {
                String taskJson  = objectMapper.writeValueAsString(task);
                String topicName = TASK_TOPIC + "-" + task.getPriority().name().toLowerCase();
                kafkaTemplate.send(topicName, task.getId(), taskJson);
                log.info("Task re-enqueued: {} -> {}", taskId, topicName);
            } catch (JsonProcessingException e) {
                log.error("Failed to re-enqueue task: {}", taskId, e);
            }
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
