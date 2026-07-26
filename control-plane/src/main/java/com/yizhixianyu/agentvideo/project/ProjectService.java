package com.yizhixianyu.agentvideo.project;

import com.yizhixianyu.agentvideo.auth.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ProjectEntity create(String ownerUserId, String name) {
        var normalized = name == null || name.isBlank() ? "Untitled video project" : name.trim();
        return repository.save(new ProjectEntity(ownerUserId, normalized));
    }

    @Transactional(readOnly = true)
    public ProjectEntity getRequired(String projectId) {
        return repository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectEntity getRequiredForUser(String projectId, String userId) {
        var project = getRequired(projectId);
        if (!project.getOwnerUserId().equals(userId)) {
            throw new AccessDeniedException("无权访问该项目");
        }
        return project;
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> list(String ownerUserId) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
    }
}
