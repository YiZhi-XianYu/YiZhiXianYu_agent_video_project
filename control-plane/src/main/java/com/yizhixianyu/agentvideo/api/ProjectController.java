package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.asset.AssetEntity;
import com.yizhixianyu.agentvideo.asset.AssetService;
import com.yizhixianyu.agentvideo.project.ProjectEntity;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.time.Instant;
import java.net.URI;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AssetService assetService;
    private final AuthService authService;

    public ProjectController(ProjectService projectService, AssetService assetService, AuthService authService) {
        this.projectService = projectService;
        this.assetService = assetService;
        this.authService = authService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(@Valid @RequestBody CreateProjectRequest request, HttpServletRequest servletRequest) {
        var user = authService.requireUser(servletRequest);
        return ProjectView.from(projectService.create(user.id(), request.name()));
    }

    @GetMapping
    public List<ProjectView> list(HttpServletRequest request) {
        var user = authService.requireUser(request);
        return projectService.list(user.id()).stream().map(ProjectView::from).toList();
    }

    @PostMapping(path = "/{projectId}/assets", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetView upload(
        @PathVariable String projectId, @RequestPart("file") MultipartFile file, HttpServletRequest request
    ) {
        projectService.getRequiredForUser(projectId, authService.requireUser(request).id());
        return AssetView.from(assetService.upload(projectId, file));
    }

    @PostMapping(path = "/{projectId}/assets/batch", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public List<AssetView> uploadBatch(
        @PathVariable String projectId,
        @RequestPart("files") List<MultipartFile> files,
        HttpServletRequest request
    ) {
        projectService.getRequiredForUser(projectId, authService.requireUser(request).id());
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one video file is required");
        }
        return files.stream().map(file -> AssetView.from(assetService.upload(projectId, file))).toList();
    }

    @GetMapping("/{projectId}/assets")
    public List<AssetView> listAssets(@PathVariable String projectId, HttpServletRequest request) {
        projectService.getRequiredForUser(projectId, authService.requireUser(request).id());
        return assetService.listByProject(projectId).stream().map(AssetView::from).toList();
    }

    @GetMapping("/{projectId}/assets/{assetId}/content")
    public ResponseEntity<Resource> assetContent(
        @PathVariable String projectId,
        @PathVariable String assetId,
        HttpServletRequest request
    ) {
        projectService.getRequiredForUser(projectId, authService.requireUser(request).id());
        var asset = assetService.getRequired(assetId);
        if (!projectId.equals(asset.getProjectId())) {
            throw new IllegalArgumentException("Asset does not belong to project: " + projectId);
        }
        var uri = URI.create(asset.getStorageUri());
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Asset is not available from local storage");
        }
        var resource = new FileSystemResource(Path.of(uri));
        if (!resource.isFile() || !resource.isReadable()) {
            throw new IllegalArgumentException("Asset content is not readable: " + assetId);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .contentType(MediaType.parseMediaType("video/mp4"))
            .contentLength(asset.getSizeBytes())
            .body(resource);
    }

    public record CreateProjectRequest(@NotBlank String name) {
    }

    public record ProjectView(String id, String name, String status, Instant createdAt, Instant updatedAt) {
        static ProjectView from(ProjectEntity entity) {
            return new ProjectView(
                entity.getId(), entity.getName(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt()
            );
        }
    }

    public record AssetView(
        String id, String projectId, String type, String status, String fileName, long sizeBytes, String contentHash,
        Instant createdAt, Instant updatedAt, String contentUrl
    ) {
        static AssetView from(AssetEntity entity) {
            return new AssetView(
                entity.getId(), entity.getProjectId(), entity.getType(), entity.getStatus(), entity.getFileName(),
                entity.getSizeBytes(), entity.getContentHash(), entity.getCreatedAt(), entity.getUpdatedAt(),
                "/api/v1/projects/" + entity.getProjectId() + "/assets/" + entity.getId() + "/content"
            );
        }
    }
}
