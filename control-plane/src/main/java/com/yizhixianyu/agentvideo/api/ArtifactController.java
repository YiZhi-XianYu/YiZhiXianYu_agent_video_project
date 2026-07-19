package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.artifact.ArtifactRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactRepository artifactRepository;

    public ArtifactController(ArtifactRepository artifactRepository) {
        this.artifactRepository = artifactRepository;
    }

    @GetMapping("/{artifactId}/content")
    public ResponseEntity<Resource> content(@PathVariable String artifactId) {
        var artifact = artifactRepository.findById(artifactId)
            .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        var uri = URI.create(artifact.getStorageUri());
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Artifact is not available from local storage");
        }
        var resource = new FileSystemResource(Path.of(uri));
        if (!resource.isFile() || !resource.isReadable()) {
            throw new IllegalArgumentException("Artifact content is not readable: " + artifactId);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .contentType(MediaType.parseMediaType(artifact.getMediaType()))
            .contentLength(artifact.getSizeBytes())
            .body(resource);
    }
}
