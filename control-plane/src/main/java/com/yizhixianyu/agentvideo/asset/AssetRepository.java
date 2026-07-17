package com.yizhixianyu.agentvideo.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetRepository extends JpaRepository<AssetEntity, String> {
    List<AssetEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}

