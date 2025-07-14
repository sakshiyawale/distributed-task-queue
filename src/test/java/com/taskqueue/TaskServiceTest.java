package com.taskqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskqueue.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    
    @Mock
    private ValueOperations<String, String> valueOperations;
    
    @Mock
    private ListOperations<String, String> listOperations;
    
    private TaskService taskService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); // Register JavaTimeModule for LocalDateTime
        taskService = new TaskService(kafkaTemplate, redisTemplate, objectMapper);
        
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    void testSubmitTask() {
        // Given
        String taskType = "test-task";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        
        // When
        Task task = taskService.submitTask(taskType, payload, Task.Priority.NORMAL);
        
        // Then
        assertNotNull(task);
        assertEquals(taskType, task.getType());
        assertEquals(Task.TaskStatus.PENDING, task.getStatus());
        assertEquals(payload, task.getPayload());
        assertEquals(Task.Priority.NORMAL, task.getPriority());
        assertNotNull(task.getId());
        assertNotNull(task.getCreatedAt());
        
        // Verify Redis operations
        verify(valueOperations).set(anyString(), anyString(), eq(24L), eq(java.util.concurrent.TimeUnit.HOURS));
        verify(listOperations).leftPush(eq("tasks:all"), eq(task.getId()));
        
        // Verify Kafka operations
        verify(kafkaTemplate).send(eq("task-queue-normal"), eq(task.getId()), anyString());
    }

    @Test
    void testGetTask() {
        // Given
        String taskId = "test-task-id";
        Task expectedTask = new Task();
        expectedTask.setId(taskId);
        expectedTask.setType("test");
        expectedTask.setStatus(Task.TaskStatus.PENDING);
        
        try {
            String taskJson = objectMapper.writeValueAsString(expectedTask);
            when(valueOperations.get("task:" + taskId)).thenReturn(taskJson);
            
            // When
            Task actualTask = taskService.getTask(taskId);
            
            // Then
            assertNotNull(actualTask);
            assertEquals(taskId, actualTask.getId());
            assertEquals("test", actualTask.getType());
            assertEquals(Task.TaskStatus.PENDING, actualTask.getStatus());
            
        } catch (Exception e) {
            fail("Test failed due to JSON processing error: " + e.getMessage());
        }
    }

    @Test
    void testGetTaskNotFound() {
        // Given
        String taskId = "non-existent-task";
        when(valueOperations.get("task:" + taskId)).thenReturn(null);
        
        // When
        Task task = taskService.getTask(taskId);
        
        // Then
        assertNull(task);
    }
} 