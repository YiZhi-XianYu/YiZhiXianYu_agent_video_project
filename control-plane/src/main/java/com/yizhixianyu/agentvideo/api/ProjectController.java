package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.asset.AssetEntity;
import com.yizhixianyu.agentvideo.asset.AssetService;
import com.yizhixianyu.agentvideo.project.ProjectEntity;
import com.yizhixianyu.agentvideo.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AssetService assetService;

    public ProjectController(ProjectService projectService, AssetService assetService) {
        this.projectService = projectService;
        this.assetService = assetService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(@Valid @RequestBody CreateProjectRequest request) {
        return ProjectView.from(projectService.create(request.name()));
    }

    @GetMapping
    public List<ProjectView> list() {
        return projectService.list().stream().map(ProjectView::from).toList();
    }

    @PostMapping(path = "/{projectId}/assets", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetView upload(@PathVariable String projectId, @RequestPart("file") MultipartFile file) {
        return AssetView.from(assetService.upload(projectId, file));
    }

    @PostMapping(path = "/{projectId}/assets/batch", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public List<AssetView> uploadBatch(
        @PathVariable String projectId,
        @RequestPart("files") List<MultipartFile> files
    ) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one video file is required");
        }
        return files.stream().map(file -> AssetView.from(assetService.upload(projectId, file))).toList();
    }

    @GetMapping("/{projectId}/assets")
    public List<AssetView> listAssets(@PathVariable String projectId) {
        projectService.getRequired(projectId);
        return assetService.listByProject(projectId).stream().map(AssetView::from).toList();
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
        Instant createdAt, Instant updatedAt
    ) {
        static AssetView from(AssetEntity entity) {
            return new AssetView(
                entity.getId(), entity.getProjectId(), entity.getType(), entity.getStatus(), entity.getFileName(),
                entity.getSizeBytes(), entity.getContentHash(), entity.getCreatedAt(), entity.getUpdatedAt()
            );
        }
    }
}
