package com.yizhixianyu.agentvideo.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;
    private final String exchange;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Timer publishTimer;

    public OutboxService(OutboxMessageRepository repository, ObjectMapper objectMapper, RabbitTemplate rabbitTemplate,
                         @Value("${app.messaging.rabbit.enabled:false}") boolean enabled,
                         @Value("${app.messaging.rabbit.task-exchange:avp.task.v1}") String exchange,
                         MeterRegistry meterRegistry) {
        this.repository = repository; this.objectMapper = objectMapper; this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled; this.exchange = exchange;
        this.publishedCounter = meterRegistry.counter("agentvideo_outbox_published_total");
        this.failedCounter = meterRegistry.counter("agentvideo_outbox_publish_failed_total");
        this.publishTimer = meterRegistry.timer("agentvideo_outbox_publish_duration");
        meterRegistry.gauge("agentvideo_outbox_pending", repository, repo -> repo.countByStatus(OutboxMessageEntity.PENDING)
            + repo.countByStatus(OutboxMessageEntity.FAILED));
    }

    @Transactional
    public OutboxMessageEntity enqueueTask(String workflowRunId, String taskRunId, Map<String, Object> payload) {
        try {
            var id = "out_" + UUID.randomUUID().toString().replace("-", "");
            var enriched = new LinkedHashMap<String, Object>(payload);
            enriched.putIfAbsent("messageId", id);
            enriched.putIfAbsent("createdAt", Instant.now().toString());
            var message = new OutboxMessageEntity(id, "TASK_RUN", taskRunId, "TASK_REQUESTED", objectMapper.writeValueAsString(enriched));
            return repository.save(message);
        } catch (Exception e) { throw new IllegalStateException("Failed to enqueue outbox task", e); }
    }

    @Scheduled(fixedDelayString = "${app.messaging.rabbit.publisher-interval-ms:1000}")
    @Transactional
    public void publishDue() {
        if (!enabled) return;
        for (var message : repository.findDue(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 50))) {
            var sample = Timer.start();
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
                publishedCounter.increment();
            } catch (Exception e) {
                message.markFailed(e.getMessage(), Instant.now().plus(Duration.ofSeconds(Math.min(300, 1L << Math.min(message.getAttempts(), 8)))));
                failedCounter.increment();
            } finally {
                sample.stop(publishTimer);
            }
        }
    }
}
