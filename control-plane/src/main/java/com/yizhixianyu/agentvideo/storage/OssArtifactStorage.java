package com.yizhixianyu.agentvideo.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import jakarta.annotation.PreDestroy;

/**
 * OSS integration seam. The SDK adapter is intentionally disabled until endpoint,
 * bucket and credentials are provided; this prevents a false-success Artifact.
 */
@Component
public class OssArtifactStorage implements ArtifactStorage {
    private final String endpoint;
    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final long signedUrlTtlSeconds;
    private volatile OSS client;

    public OssArtifactStorage(@Value("${app.storage.oss.endpoint:}") String endpoint,
                              @Value("${app.storage.oss.region:}") String region,
                              @Value("${app.storage.oss.bucket:}") String bucket,
                              @Value("${app.storage.oss.access-key-id:}") String accessKeyId,
                              @Value("${app.storage.oss.access-key-secret:}") String accessKeySecret,
                              @Value("${app.storage.oss.signed-url-ttl-seconds:600}") long signedUrlTtlSeconds) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.region = region == null ? "" : region.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.accessKeySecret = accessKeySecret == null ? "" : accessKeySecret.trim();
        this.signedUrlTtlSeconds = Math.max(60, Math.min(signedUrlTtlSeconds, 3600));
    }

    public boolean configured() {
        return !endpoint.isBlank() && !bucket.isBlank() && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
    }

    @Override
    public StoredObject store(String projectId, String category, String fileName, InputStream input,
                              String mediaType) {
        if (!configured()) throw new IllegalStateException("OSS provider requires endpoint, bucket and credentials");
        var safeProject = sanitize(projectId);
        var safeCategory = category == null ? "artifacts" : category.replace('\\', '/').replaceAll("[^\\p{L}\\p{N}._/-]", "_");
        var safeName = sanitize(fileName);
        var temp = Path.of(System.getProperty("java.io.tmpdir"), "agentvideo-oss-" + UUID.randomUUID());
        try {
            Files.copy(input, temp);
            var hash = sha256(temp);
            var key = "projects/" + safeProject + "/" + safeCategory + "/" + hash + "-" + safeName;
            var metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(temp));
            metadata.setContentType(mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType);
            getClient().putObject(new PutObjectRequest(bucket, key, temp.toFile(), metadata));
            return new StoredObject(safeName, "oss://" + bucket + "/" + key, Files.size(temp), hash, metadata.getContentType());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stage artifact for OSS", e);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    @Override
    public Resource resource(String storageUri) {
        if (!configured()) throw new IllegalStateException("OSS provider requires endpoint, bucket and credentials");
        var uri = java.net.URI.create(storageUri);
        if (!"oss".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Unsupported OSS URI: " + storageUri);
        var path = uri.getPath();
        var key = path == null ? "" : path.replaceFirst("^/", "");
        if (key.isBlank()) throw new IllegalArgumentException("OSS object key is empty");
        var request = new GeneratePresignedUrlRequest(bucket, key, com.aliyun.oss.HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + Duration.ofSeconds(signedUrlTtlSeconds).toMillis()));
        URL signed = getClient().generatePresignedUrl(request);
        return new UrlResource(signed);
    }

    @Override
    public URI createReadUrl(String storageUri) {
        if (!configured()) throw new IllegalStateException("OSS provider requires endpoint, bucket and credentials");
        var uri = java.net.URI.create(storageUri);
        if (!"oss".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Unsupported OSS URI: " + storageUri);
        var key = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (key.isBlank()) throw new IllegalArgumentException("OSS object key is empty");
        var request = new GeneratePresignedUrlRequest(bucket, key, com.aliyun.oss.HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + Duration.ofSeconds(signedUrlTtlSeconds).toMillis()));
        try {
            return getClient().generatePresignedUrl(request).toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Failed to create OSS read URL", e);
        }
    }

    private OSS getClient() {
        var current = client;
        if (current == null) synchronized (this) {
            current = client;
            if (current == null) client = current = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        }
        return current;
    }

    @PreDestroy
    public void close() { if (client != null) client.shutdown(); }

    private static String sanitize(String value) {
        var name = value == null || value.isBlank() ? "artifact.bin" : Path.of(value).getFileName().toString();
        return name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                var buffer = new byte[8192]; int read;
                while ((read = stream.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
