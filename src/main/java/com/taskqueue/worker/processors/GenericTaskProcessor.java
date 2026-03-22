package com.taskqueue.worker.processors;

import com.taskqueue.worker.TaskProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fallback processor for any unrecognised task type.
 * TaskWorker falls back to this when no specific processor is registered.
 */
@Component
public class GenericTaskProcessor implements TaskProcessor {

    public static final String GENERIC_TYPE = "GENERIC";

    @Override
    public String getType() {
        return GENERIC_TYPE;
    }

    @Override
    public String process(Map<String, Object> payload) throws Exception {
        Thread.sleep(1000);
        return "Generic task processed with payload: " + payload;
    }
}
