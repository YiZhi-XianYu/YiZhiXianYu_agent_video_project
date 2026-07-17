package com.yizhixianyu.agentvideo.project;

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
    public ProjectEntity create(String name) {
        var normalized = name == null || name.isBlank() ? "Untitled video project" : name.trim();
        return repository.save(new ProjectEntity(normalized));
    }

    @Transactional(readOnly = true)
    public ProjectEntity getRequired(String projectId) {
        return repository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> list() {
        return repository.findAll();
    }
}

