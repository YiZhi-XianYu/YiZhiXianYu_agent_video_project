package com.yizhixianyu.agentvideo.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class LocalStorageService implements ArtifactStorage {

    private final Path root;

    public LocalStorageService(@Value("${app.storage.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredObject store(String projectId, String category, String fileName, InputStream input,
                              String mediaType) {
        var originalName = sanitize(fileName);
        var targetDir = root.resolve("projects").resolve(sanitize(projectId)).resolve(sanitize(category));
        var target = targetDir.resolve(UUID.randomUUID() + "-" + originalName).normalize();
        if (!target.startsWith(targetDir)) throw new IllegalArgumentException("Invalid file name");
        try {
            Files.createDirectories(targetDir);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredObject(originalName, target.toUri().toString(), Files.size(target), sha256(target),
                mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to store artifact", exc);
        }
    }

    public StoredFile storeVideo(String projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Video file is required");
        }
        StoredObject object;
        try (var input = file.getInputStream()) {
            object = store(projectId, "assets", file.getOriginalFilename(), input, file.getContentType());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded video", e);
        }
        return new StoredFile(object.fileName(), object.storageUri(), object.sizeBytes(), object.contentHash());
    }

    @Override
    public Resource resource(String storageUri) {
        try {
            var uri = java.net.URI.create(storageUri);
            if (!"file".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Unsupported storage URI: " + storageUri);
            return new FileSystemResource(Path.of(uri));
        } catch (RuntimeException e) { throw e; }
    }

    private static String sanitize(String name) {
        var value = name == null || name.isBlank() ? "video.bin" : Path.of(name).getFileName().toString();
        return value.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is not available", exc);
        }
    }

    public record StoredFile(String fileName, String storageUri, long sizeBytes, String contentHash) {
    }
}
