package com.yizhixianyu.agentvideo.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;
    private final String exchange;

    public OutboxService(OutboxMessageRepository repository, ObjectMapper objectMapper, RabbitTemplate rabbitTemplate,
                         @Value("${app.messaging.rabbit.enabled:false}") boolean enabled,
                         @Value("${app.messaging.rabbit.task-exchange:avp.task.v1}") String exchange) {
        this.repository = repository; this.objectMapper = objectMapper; this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled; this.exchange = exchange;
    }

    @Transactional
    public OutboxMessageEntity enqueueTask(String workflowRunId, String taskRunId, Map<String, Object> payload) {
        try {
            var id = "out_" + UUID.randomUUID().toString().replace("-", "");
            var message = new OutboxMessageEntity(id, "TASK_RUN", taskRunId, "TASK_REQUESTED", objectMapper.writeValueAsString(payload));
            return repository.save(message);
        } catch (Exception e) { throw new IllegalStateException("Failed to enqueue outbox task", e); }
    }

    @Scheduled(fixedDelayString = "${app.messaging.rabbit.publisher-interval-ms:1000}")
    @Transactional
    public void publishDue() {
        if (!enabled) return;
        for (var message : repository.findDue(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 50))) {
            try {
                var payload = objectMapper.readTree(message.getPayloadJson());
                var routingKey = "task." + payload.path("resourceGroup").asText("LIGHT").toLowerCase() + ".requested";
                rabbitTemplate.invoke(operations -> {
                    operations.convertAndSend(exchange, routingKey, payload.toString(), m -> {
                        m.getMessageProperties().setMessageId(message.getMessageId());
                        m.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                        m.getMessageProperties().setContentType("application/json");
                        return m;
                    });
                    operations.waitForConfirmsOrDie(5000);
                    return null;
                });
                message.markPublished();
            } catch (Exception e) {
                message.markFailed(e.getMessage(), Instant.now().plus(Duration.ofSeconds(Math.min(300, 1L << Math.min(message.getAttempts(), 8)))));
            }
        }
    }
}
