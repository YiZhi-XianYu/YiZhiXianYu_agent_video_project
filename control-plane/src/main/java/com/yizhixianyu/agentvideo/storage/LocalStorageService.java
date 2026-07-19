package com.yizhixianyu.agentvideo.storage;

import org.springframework.beans.factory.annotation.Value;
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
public class LocalStorageService {

    private final Path root;

    public LocalStorageService(@Value("${app.storage.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public StoredFile storeVideo(String projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Video file is required");
        }
        var originalName = sanitize(file.getOriginalFilename());
        var targetDir = root.resolve("projects").resolve(projectId).resolve("assets");
        var target = targetDir.resolve(UUID.randomUUID() + "-" + originalName).normalize();
        if (!target.startsWith(targetDir)) {
            throw new IllegalArgumentException("Invalid file name");
        }
        try {
            Files.createDirectories(targetDir);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            var hash = sha256(target);
            return new StoredFile(originalName, target.toUri().toString(), Files.size(target), hash);
        } catch (IOException exc) {
            throw new IllegalStateException("Failed to store uploaded video", exc);
        }
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
