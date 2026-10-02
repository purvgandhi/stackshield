package com.stacktrial.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImportedRepositoryRepository extends JpaRepository<ImportedRepository, UUID> {

    List<ImportedRepository> findAllByOrderByCreatedAtDesc();

    Optional<ImportedRepository> findByRepoUrl(String repoUrl);
}
