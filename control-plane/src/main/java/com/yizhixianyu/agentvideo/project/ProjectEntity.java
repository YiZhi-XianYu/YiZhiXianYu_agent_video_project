package com.yizhixianyu.agentvideo.project;

import com.yizhixianyu.agentvideo.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class ProjectEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String ownerUserId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 30)
    private String status;

    protected ProjectEntity() {
    }

    public ProjectEntity(String ownerUserId, String name) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.status = "ACTIVE";
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }
}

