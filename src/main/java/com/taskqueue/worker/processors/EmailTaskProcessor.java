package com.taskqueue.worker.processors;

import com.taskqueue.worker.TaskProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmailTaskProcessor implements TaskProcessor {

    @Override
    public String getType() {
        return "EMAIL_SEND";
    }

    @Override
    public String process(Map<String, Object> payload) throws Exception {
        String recipient = (String) payload.get("recipient");
        String subject   = (String) payload.get("subject");
        Thread.sleep(2000);
        return "Email sent to " + recipient + " with subject: " + subject;
    }
}
