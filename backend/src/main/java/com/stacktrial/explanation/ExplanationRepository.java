package com.stacktrial.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExplanationRepository extends JpaRepository<Explanation, UUID> {

    Optional<Explanation> findByTrialId(UUID trialId);
}
