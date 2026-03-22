package com.taskqueue.worker.processors;

import com.taskqueue.worker.TaskProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DataExportTaskProcessor implements TaskProcessor {

    @Override
    public String getType() {
        return "DATA_EXPORT";
    }

    @Override
    public String process(Map<String, Object> payload) throws Exception {
        String format      = (String) payload.get("format");
        Integer recordCount = (Integer) payload.get("recordCount");
        Thread.sleep(3000);
        return "Exported " + recordCount + " records in " + format + " format";
    }
}
