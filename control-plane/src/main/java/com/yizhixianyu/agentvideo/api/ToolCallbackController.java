package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.execution.WorkflowExecutionService;
import com.yizhixianyu.agentvideo.toolclient.ToolServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tool-callbacks")
public class ToolCallbackController {

    private final WorkflowExecutionService workflowService;

    public ToolCallbackController(WorkflowExecutionService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void callback(@RequestBody ToolServiceClient.ToolExecutionResponse response) {
        workflowService.applyToolResult(response);
    }
}

