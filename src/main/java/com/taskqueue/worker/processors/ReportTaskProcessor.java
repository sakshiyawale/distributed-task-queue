package com.taskqueue.worker.processors;

import com.taskqueue.worker.TaskProcessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReportTaskProcessor implements TaskProcessor {

    @Override
    public String getType() {
        return "REPORT_GENERATE";
    }

    @Override
    public String process(Map<String, Object> payload) throws Exception {
        String reportType = (String) payload.get("reportType");
        String dateRange  = (String) payload.get("dateRange");
        Thread.sleep(8000);
        return "Generated " + reportType + " report for " + dateRange;
    }
}
