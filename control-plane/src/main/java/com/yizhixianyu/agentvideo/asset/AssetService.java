package com.yizhixianyu.agentvideo.asset;

import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.storage.LocalStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class AssetService {

    private final AssetRepository repository;
    private final ProjectService projectService;
    private final LocalStorageService storageService;

    public AssetService(
        AssetRepository repository,
        ProjectService projectService,
        LocalStorageService storageService
    ) {
        this.repository = repository;
        this.projectService = projectService;
        this.storageService = storageService;
    }

    @Transactional
    public AssetEntity upload(String projectId, MultipartFile file) {
        projectService.getRequired(projectId);
        var stored = storageService.storeVideo(projectId, file);
        return repository.save(new AssetEntity(
            projectId,
            stored.fileName(),
            stored.storageUri(),
            stored.sizeBytes(),
            stored.contentHash()
        ));
    }

    @Transactional(readOnly = true)
    public AssetEntity getRequired(String assetId) {
        return repository.findById(assetId)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));
    }

    @Transactional(readOnly = true)
    public AssetEntity getRequiredAvailable(String assetId) {
        var asset = getRequired(assetId);
        if (!asset.isAvailable()) {
            throw new IllegalArgumentException("Asset is no longer available in the project library: " + assetId);
        }
        return asset;
    }

    @Transactional(readOnly = true)
    public List<AssetEntity> listByProject(String projectId) {
        return repository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            projectId, AssetEntity.STATUS_AVAILABLE
        );
    }

    @Transactional
    public void removeFromLibrary(String projectId, String assetId) {
        var asset = getRequired(assetId);
        if (!projectId.equals(asset.getProjectId())) {
            throw new IllegalArgumentException("Asset does not belong to project: " + projectId);
        }
        if (asset.isAvailable()) {
            asset.removeFromLibrary();
            repository.save(asset);
        }
    }
}

