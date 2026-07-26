package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactRepository artifactRepository;
    private final ProjectService projectService;
    private final AuthService authService;

    public ArtifactController(
        ArtifactRepository artifactRepository, ProjectService projectService, AuthService authService
    ) {
        this.artifactRepository = artifactRepository;
        this.projectService = projectService;
        this.authService = authService;
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
        var uri = URI.create(artifact.getStorageUri());
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Artifact is not available from local storage");
        }
        var resource = new FileSystemResource(Path.of(uri));
        if (!resource.isFile() || !resource.isReadable()) {
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
}
