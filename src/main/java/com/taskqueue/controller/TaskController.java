package com.taskqueue.controller;

import com.taskqueue.Task;
import com.taskqueue.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// @CrossOrigin removed — CORS is handled centrally in SecurityConfig.corsConfigurationSource()
// Previously had @CrossOrigin(origins = "*") which bypassed the configured allowed-origins
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
@Slf4j
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> getRoot() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Distributed Task Queue System");
        response.put("version", "1.0.0");
        response.put("endpoints", Map.of(
            "submit_task",   "POST /tasks",
            "get_all_tasks", "GET /tasks?page=0&size=20",
            "get_task",      "GET /tasks/{taskId}",
            "get_workers",   "GET /tasks/workers",
            "get_statistics","GET /tasks/statistics"
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

    // Admin-only: enforced both at URL level (SecurityConfig) and method level (@PreAuthorize)
    @DeleteMapping("/{taskId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteTask(@PathVariable String taskId) {
        boolean result = taskService.deleteTask(taskId);
        return result ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Task> getTask(@PathVariable String taskId) {
        Task task = taskService.getTask(taskId);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    /**
     * Paginated task list. Defaults to page=0, size=20.
     * Uses Redis LRANGE offset/limit — does not load all tasks into memory.
     */
    @GetMapping
    public ResponseEntity<List<Task>> getAllTasks(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Task> tasks = taskService.getAllTasks(page, size);
        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Task>> getTasksByStatus(@PathVariable Task.TaskStatus status) {
        return ResponseEntity.ok(taskService.getTasksByStatus(status));
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getTaskStatistics() {
        return ResponseEntity.ok(taskService.getTaskStatistics());
    }

    @GetMapping("/workers")
    public ResponseEntity<List<Map<String, Object>>> getActiveWorkers() {
        return ResponseEntity.ok(taskService.getActiveWorkers());
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

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
