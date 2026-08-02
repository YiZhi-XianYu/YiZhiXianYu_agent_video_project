package com.yizhixianyu.agentvideo.outbox;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_messages")
public class OutboxMessageEntity extends BaseEntity {
    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED = "FAILED";

    @Column(nullable = false, unique = true, length = 80) private String messageId;
    @Column(nullable = false, length = 60) private String aggregateType;
    @Column(nullable = false, length = 80) private String aggregateId;
    @Column(nullable = false, length = 80) private String eventType;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String payloadJson;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private int attempts;
    private Instant nextAttemptAt;
    private Instant publishedAt;
    @Column(length = 2000) private String lastError;

    protected OutboxMessageEntity() {}
    public OutboxMessageEntity(String messageId, String aggregateType, String aggregateId, String eventType, String payloadJson) {
        this.messageId = messageId; this.aggregateType = aggregateType; this.aggregateId = aggregateId;
        this.eventType = eventType; this.payloadJson = payloadJson; this.status = PENDING; this.attempts = 0;
        this.nextAttemptAt = Instant.now();
    }
    public void markPublished() { status = PUBLISHED; publishedAt = Instant.now(); lastError = null; }
    public void markFailed(String error, Instant retryAt) { status = FAILED; attempts++; lastError = error; nextAttemptAt = retryAt; }
    public void retry() { status = PENDING; nextAttemptAt = Instant.now(); }
    public String getMessageId() { return messageId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public int getAttempts() { return attempts; }
}
