package com.yizhixianyu.agentvideo.asset;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "assets")
public class AssetEntity extends BaseEntity {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_REMOVED = "REMOVED";

    @Column(nullable = false, length = 40)
    private String projectId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(nullable = false, length = 2000)
    private String storageUri;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String contentHash;

    protected AssetEntity() {
    }

    public AssetEntity(String projectId, String fileName, String storageUri, long sizeBytes, String contentHash) {
        this.projectId = projectId;
        this.type = "VIDEO_SOURCE";
        this.status = STATUS_AVAILABLE;
        this.fileName = fileName;
        this.storageUri = storageUri;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStorageUri() {
        return storageUri;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public boolean isAvailable() {
        return STATUS_AVAILABLE.equals(status);
    }

    public void removeFromLibrary() {
        this.status = STATUS_REMOVED;
    }
}
