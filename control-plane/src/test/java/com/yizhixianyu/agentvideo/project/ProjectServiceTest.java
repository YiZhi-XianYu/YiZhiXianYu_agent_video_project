package com.yizhixianyu.agentvideo.project;

import com.yizhixianyu.agentvideo.auth.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository repository;

    @Test
    void projectOwnerIsEnforced() {
        var service = new ProjectService(repository);
        when(repository.findById("project-1")).thenReturn(Optional.of(new ProjectEntity("owner-1", "Project")));

        assertThatThrownBy(() -> service.getRequiredForUser("project-1", "owner-2"))
            .isInstanceOf(AccessDeniedException.class);
    }
}
