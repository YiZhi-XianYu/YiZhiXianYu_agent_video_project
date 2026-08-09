package com.yizhixianyu.agentvideo.toolclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ToolServiceClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;

    public ToolServiceClient(
        ObjectMapper objectMapper,
        @Value("${app.tool-service.base-url}") String baseUrl
    ) {
        this.objectMapper = objectMapper;
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    public AcceptedExecution createExecution(CreateToolExecutionRequest request) {
        var httpRequest = HttpRequest.newBuilder(resolve("api/v1/tool-executions"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8))
            .build();
        return send(httpRequest, AcceptedExecution.class);
    }

    public ToolExecutionResponse getExecution(String executionId) {
        var httpRequest = HttpRequest.newBuilder(resolve("api/v1/tool-executions/" + executionId))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();
        return send(httpRequest, ToolExecutionResponse.class);
    }

    public WorkflowIntentResponse requestWorkflowIntent(WorkflowIntentRequest request) {
        var httpRequest = HttpRequest.newBuilder(resolve("api/v1/workflow-planning/intent"))
            .timeout(Duration.ofSeconds(35))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(toJson(request), StandardCharsets.UTF_8))
            .build();
        return send(httpRequest, WorkflowIntentResponse.class);
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                    "Tool Service returned HTTP " + response.statusCode() + ": " + response.body()
                );
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tool Service request was interrupted", exc);
        } catch (IOException exc) {
            throw new IllegalStateException("Tool Service request failed", exc);
        }
    }

    private URI resolve(String path) {
        return baseUri.resolve(path);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new IllegalArgumentException("Failed to serialize Tool Service request", exc);
        }
    }

    public record ArtifactInput(String artifactId, String uri, String fileName) {
    }

    public record TraceContext(String traceId, String sessionId, String turnId, String planId,
                               String workflowRunId, String taskRunId) {
        public TraceContext(String traceId, String workflowRunId, String taskRunId) {
            this(traceId, null, null, null, workflowRunId, taskRunId);
        }
    }

    public record CreateToolExecutionRequest(
        String tool,
        String version,
        String idempotencyKey,
        Map<String, ArtifactInput> inputs,
        Map<String, Object> parameters,
        String callbackUrl,
        TraceContext traceContext
    ) {
    }

    public record AcceptedExecution(String executionId, String status, String statusUrl) {
    }

    public record ArtifactOutput(
        String artifactId,
        String type,
        String uri,
        String mediaType,
        long size,
        String contentHash,
        JsonNode metadata
    ) {
    }

    public record ToolError(String code, String message, boolean retryable, JsonNode details) {
    }

    public record ToolExecutionResponse(
        String executionId,
        String idempotencyKey,
        String tool,
        String version,
        String status,
        int progress,
        List<ArtifactOutput> outputs,
        ToolError error
    ) {
    }

    public record WorkflowIntentRequest(
        String goal,
        String targetDuration,
        int assetCount,
        List<String> availableCapabilities,
        Integer targetDurationMs
    ) {
        public WorkflowIntentRequest(String goal, String targetDuration, int assetCount,
                                      List<String> availableCapabilities) {
            this(goal, targetDuration, assetCount, availableCapabilities, null);
        }
    }

    public record WorkflowIntentResponse(
        boolean llmUsed,
        Map<String, String> capabilities,
        String pacing,
        String explanation,
        int targetDurationMs,
        Map<String, Object> modelRoute
    ) {}
}
