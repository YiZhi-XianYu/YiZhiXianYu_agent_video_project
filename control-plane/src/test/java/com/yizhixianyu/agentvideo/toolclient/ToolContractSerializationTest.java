package com.yizhixianyu.agentvideo.toolclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolContractSerializationTest {

    @Test
    void serializesTheToolExecutionContract() throws Exception {
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            "video.probe",
            "1.0.0",
            "probe:task:1",
            Map.of("video", new ToolServiceClient.ArtifactInput("asset-1", "file:///sample.mp4", "sample.mp4")),
            Map.of(),
            "http://127.0.0.1:8080/internal/tool-callbacks",
            new ToolServiceClient.TraceContext("trace-1", "workflow-1", "task-1")
        );

        var json = new ObjectMapper().writeValueAsString(request);

        assertThat(json).contains("\"tool\":\"video.probe\"");
        assertThat(json).contains("\"idempotencyKey\":\"probe:task:1\"");
        assertThat(json).contains("\"callbackUrl\":\"http://127.0.0.1:8080/internal/tool-callbacks\"");
    }

    @Test
    void serializesTheSelectedProxyQuality() throws Exception {
        var request = new ToolServiceClient.CreateToolExecutionRequest(
            "video.proxy-generate",
            "1.0.0",
            "proxy:task:1",
            Map.of("video", new ToolServiceClient.ArtifactInput("asset-1", "file:///sample.mp4", "sample.mp4")),
            Map.of("quality", "4K"),
            "http://127.0.0.1:8080/internal/tool-callbacks",
            new ToolServiceClient.TraceContext("trace-1", "workflow-1", "task-1")
        );

        var json = new ObjectMapper().writeValueAsString(request);

        assertThat(json).contains("\"quality\":\"4K\"");
    }
}
