package com.taskqueue.worker.processors;

import com.taskqueue.worker.TaskProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImageTaskProcessor implements TaskProcessor {

    @Override
    public String getType() {
        return "IMAGE_PROCESS";
    }

    @Override
    public String process(Map<String, Object> payload) throws Exception {
        String imageUrl  = (String) payload.get("imageUrl");
        String operation = (String) payload.get("operation");
        Thread.sleep(5000);
        return "Image processed: " + imageUrl + " with operation: " + operation;
    }
}
