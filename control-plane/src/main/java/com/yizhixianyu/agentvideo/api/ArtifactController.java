package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import com.yizhixianyu.agentvideo.storage.ArtifactStorage;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactRepository artifactRepository;
    private final ProjectService projectService;
    private final AuthService authService;
    private final ArtifactStorage storage;

    public ArtifactController(
        ArtifactRepository artifactRepository, ProjectService projectService, AuthService authService,
        ArtifactStorage storage
    ) {
        this.artifactRepository = artifactRepository;
        this.projectService = projectService;
        this.authService = authService;
        this.storage = storage;
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<Resource> content(
        @PathVariable String artifactId,
        @RequestParam(defaultValue = "false") boolean download,
        HttpServletRequest request
    ) {
        var artifact = artifactRepository.findById(artifactId)
            .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        projectService.getRequiredForUser(artifact.getProjectId(), authService.requireUser(request).id());
        // Binary media is redirected; structured JSON/SRT remains same-origin so
        // workflow metadata fetches do not depend on Bucket CORS.
        var directUrl = isBinaryMedia(artifact.getMediaType())
            ? storage.createReadUrl(artifact.getStorageUri()) : null;
        if (directUrl != null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TEMPORARY_REDIRECT)
                .location(directUrl)
                // Signed URL TTL is 10 minutes by default; cache the redirect only for that window.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .build();
        }
        var resource = storage.resource(artifact.getStorageUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("Artifact content is not readable: " + artifactId);
        }
        var response = ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .contentType(MediaType.parseMediaType(artifact.getMediaType()))
            .contentLength(artifact.getSizeBytes());
        if (download) {
            response.header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(resource.getFilename(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            );
        }
        return response.body(resource);
    }

    private static boolean isBinaryMedia(String mediaType) {
        if (mediaType == null) return false;
        var type = mediaType.toLowerCase();
        return type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/");
    }
}
