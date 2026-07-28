package com.yizhixianyu.agentvideo.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {
    Optional<ArtifactEntity> findByExternalArtifactId(String externalArtifactId);
    List<ArtifactEntity> findByProducerTaskRunId(String producerTaskRunId);
    List<ArtifactEntity> findByProducerTaskRunIdIn(List<String> producerTaskRunIds);
    List<ArtifactEntity> findTop100ByProjectIdAndTypeOrderByCreatedAtDesc(String projectId, String type);
}
