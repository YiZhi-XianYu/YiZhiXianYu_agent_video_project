package com.yizhixianyu.agentvideo.artifact;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "artifacts")
public class ArtifactEntity extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String externalArtifactId;

    @Column(nullable = false, length = 40)
    private String projectId;

    @Column(nullable = false, length = 40)
    private String producerTaskRunId;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 2000)
    private String storageUri;

    @Column(nullable = false, length = 120)
    private String mediaType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String metadataJson;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(
        String externalArtifactId,
        String projectId,
        String producerTaskRunId,
        String type,
        String storageUri,
        String mediaType,
        long sizeBytes,
        String contentHash,
        String metadataJson
    ) {
        this.externalArtifactId = externalArtifactId;
        this.projectId = projectId;
        this.producerTaskRunId = producerTaskRunId;
        this.type = type;
        this.storageUri = storageUri;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.metadataJson = metadataJson;
    }

    public String getExternalArtifactId() {
        return externalArtifactId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getProducerTaskRunId() {
        return producerTaskRunId;
    }

    public String getType() {
        return type;
    }

    public String getStorageUri() {
        return storageUri;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getMetadataJson() {
        return metadataJson;
    }
}
