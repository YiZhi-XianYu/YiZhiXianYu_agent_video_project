package com.yizhixianyu.agentvideo.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;

/** Unified immutable artifact/object storage boundary. */
public interface ArtifactStorage {
    StoredObject store(String projectId, String category, String fileName, InputStream input,
                       String mediaType);

    default StoredObject store(String projectId, String category, MultipartFile file) {
        try (var input = file.getInputStream()) {
            return store(projectId, category, file.getOriginalFilename(), input, file.getContentType());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read uploaded file", e);
        }
    }

    default StoredObject storeBytes(String projectId, String category, String fileName, byte[] bytes,
                                    String mediaType) {
        return store(projectId, category, fileName, new java.io.ByteArrayInputStream(bytes), mediaType);
    }

    Resource resource(String storageUri);

    /** Direct short-lived URL for browser media delivery; null when provider has no redirect URL. */
    default URI createReadUrl(String storageUri) { return null; }

    record StoredObject(String fileName, String storageUri, long sizeBytes, String contentHash, String mediaType) {}
}
