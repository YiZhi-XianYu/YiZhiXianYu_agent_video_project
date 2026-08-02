package com.yizhixianyu.agentvideo.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;

/** Provider boundary. Local is production-safe default; OSS adapter can be enabled when credentials exist. */
@Primary
@Service
public class ArtifactStorageRouter implements ArtifactStorage {
    private final LocalStorageService local;
    private final OssArtifactStorage oss;
    private final String provider;

    public ArtifactStorageRouter(LocalStorageService local, OssArtifactStorage oss,
                                 @Value("${app.storage.provider:local}") String provider) {
        this.local = local;
        this.oss = oss;
        this.provider = provider == null ? "local" : provider.trim().toLowerCase();
    }

    @Override
    public StoredObject store(String projectId, String category, String fileName, InputStream input, String mediaType) {
        // Keep structured artifacts (JSON/SRT/manifest) on the shared local volume.
        // Binary media (video/audio/image) is promoted to OSS for durable, direct delivery.
        if (!isBinaryMedia(fileName, mediaType)) return local.store(projectId, category, fileName, input, mediaType);
        if ("local".equals(provider)) return local.store(projectId, category, fileName, input, mediaType);
        if ("oss".equals(provider)) return oss.store(projectId, category, fileName, input, mediaType);
        throw new IllegalStateException("Unknown storage provider: " + provider);
    }

    private static boolean isBinaryMedia(String fileName, String mediaType) {
        if (mediaType != null) {
            var type = mediaType.toLowerCase();
            if (type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/")) return true;
        }
        var name = fileName == null ? "" : fileName.toLowerCase();
        return name.matches(".*\\.(mp4|mov|m4v|webm|mkv|avi|mp3|wav|m4a|aac|ogg|flac|jpg|jpeg|png|webp|gif)$");
    }

    @Override
    public Resource resource(String storageUri) {
        var scheme = java.net.URI.create(storageUri).getScheme();
        if ("file".equalsIgnoreCase(scheme)) return local.resource(storageUri);
        if ("oss".equalsIgnoreCase(scheme)) return oss.resource(storageUri);
        throw new IllegalArgumentException("Storage provider for URI is unavailable: " + scheme);
    }

    @Override
    public URI createReadUrl(String storageUri) {
        var scheme = java.net.URI.create(storageUri).getScheme();
        if ("oss".equalsIgnoreCase(scheme)) return oss.createReadUrl(storageUri);
        return null;
    }
}
