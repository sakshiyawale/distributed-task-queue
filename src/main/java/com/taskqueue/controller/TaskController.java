package com.taskqueue.controller;

import com.taskqueue.Task;
import com.taskqueue.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class TaskController {
    
    private final TaskService taskService;

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> getRoot() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Distributed Task Queue System");
        response.put("version", "1.0.0");
        response.put("endpoints", Map.of(
            "submit_task", "POST /tasks",
            "get_all_tasks", "GET /tasks",
            "get_task", "GET /tasks/{taskId}",
            "get_workers", "GET /tasks/workers",
            "get_statistics", "GET /tasks/statistics"
        ));
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Task> submitTask(@RequestBody TaskSubmissionRequest request) {
        try {
            Task task = taskService.submitTask(
                request.getType(), 
                request.getPayload(), 
                request.getPriority()
            );
            return ResponseEntity.ok(task);
        } catch (Exception e) {
            log.error("Failed to submit task", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        boolean result = taskService.cancelTask(taskId);
        return result ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<?> pauseTask(@PathVariable String taskId) {
        boolean result = taskService.pauseTask(taskId);
        return result ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<?> retryTask(@PathVariable String taskId) {
        boolean result = taskService.retryTask(taskId);
        return result ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> deleteTask(@PathVariable String taskId) {
        boolean result = taskService.deleteTask(taskId);
        return result ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Task> getTask(@PathVariable String taskId) {
        Task task = taskService.getTask(taskId);
        if (task != null) {
            return ResponseEntity.ok(task);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping
    public ResponseEntity<List<Task>> getAllTasks() {
        List<Task> tasks = taskService.getAllTasks();
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Task>> getTasksByStatus(@PathVariable Task.TaskStatus status) {
        List<Task> tasks = taskService.getTasksByStatus(status);
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getTaskStatistics() {
        Map<String, Object> stats = taskService.getTaskStatistics();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/workers")
    public ResponseEntity<List<Map<String, Object>>> getActiveWorkers() {
        List<Map<String, Object>> workers = taskService.getActiveWorkers();
        return ResponseEntity.ok(workers);
    }

    // DTOs
    public static class TaskSubmissionRequest {
        private String type;
        private Map<String, Object> payload;
        private Task.Priority priority;

        public TaskSubmissionRequest() {}

        public TaskSubmissionRequest(String type, Map<String, Object> payload, Task.Priority priority) {
            this.type = type;
            this.payload = payload;
            this.priority = priority;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Map<String, Object> getPayload() { return payload; }
        public void setPayload(Map<String, Object> payload) { this.payload = payload; }

        public Task.Priority getPriority() { return priority; }
        public void setPriority(Task.Priority priority) { this.priority = priority; }
    }
}