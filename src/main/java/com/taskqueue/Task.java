package com.taskqueue;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    private String id;
    private String type;
    private TaskStatus status;
    private String workerId;
    private Map<String, Object> payload;
    private String result;
    private String error;
    private int retryCount;
    private int maxRetries;
    private Priority priority;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;
    
    private long executionTimeMs;

    public enum TaskStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, RETRYING, PAUSED
    }

    public enum Priority {
        LOW(1), NORMAL(2), HIGH(3), URGENT(4);
        
        private final int level;
        
        Priority(int level) {
            this.level = level;
        }
        
        public int getLevel() {
            return level;
        }
    }
}