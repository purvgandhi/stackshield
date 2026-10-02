package com.stacktrial.execution.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRunRepository extends JpaRepository<ProjectRun, UUID> {
    List<ProjectRun> findByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
}
