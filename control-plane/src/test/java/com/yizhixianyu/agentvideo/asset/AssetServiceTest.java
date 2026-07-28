package com.yizhixianyu.agentvideo.asset;

import com.yizhixianyu.agentvideo.project.ProjectService;
import com.yizhixianyu.agentvideo.storage.LocalStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock private AssetRepository repository;
    @Mock private ProjectService projectService;
    @Mock private LocalStorageService storageService;

    private AssetService service;

    @BeforeEach
    void setUp() {
        service = new AssetService(repository, projectService, storageService);
    }

    @Test
    void removingAssetOnlyHidesItFromTheProjectLibrary() {
        var asset = asset("project-1");
        when(repository.findById("asset-1")).thenReturn(Optional.of(asset));

        service.removeFromLibrary("project-1", "asset-1");

        assertThat(asset.getStatus()).isEqualTo(AssetEntity.STATUS_REMOVED);
        assertThat(service.getRequired("asset-1")).isSameAs(asset);
        verify(repository).save(asset);
    }

    @Test
    void libraryListingOnlyReturnsAvailableAssets() {
        var available = asset("project-1");
        when(repository.findByProjectIdAndStatusOrderByCreatedAtDesc(
            "project-1", AssetEntity.STATUS_AVAILABLE
        )).thenReturn(List.of(available));

        assertThat(service.listByProject("project-1")).containsExactly(available);
    }

    @Test
    void removedAssetCannotStartANewWorkflow() {
        var asset = asset("project-1");
        asset.removeFromLibrary();
        when(repository.findById("asset-1")).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.getRequiredAvailable("asset-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no longer available");
        assertThat(service.getRequired("asset-1")).isSameAs(asset);
    }

    @Test
    void cannotRemoveAssetThroughAnotherProject() {
        when(repository.findById("asset-1")).thenReturn(Optional.of(asset("project-1")));

        assertThatThrownBy(() -> service.removeFromLibrary("project-2", "asset-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong to project");
    }

    private AssetEntity asset(String projectId) {
        return new AssetEntity(projectId, "video.mp4", "file:///runtime/video.mp4", 100, "hash");
    }
}
