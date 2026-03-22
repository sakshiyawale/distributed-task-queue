package com.taskqueue.worker;

import java.util.Map;

/**
 * Strategy interface for task processors.
 * Add a new task type by implementing this interface and annotating with @Component —
 * TaskWorker picks it up automatically via Spring's dependency injection.
 * No changes to TaskWorker are needed (Open/Closed Principle).
 */
public interface TaskProcessor {
    String getType();
    String process(Map<String, Object> payload) throws Exception;
}
