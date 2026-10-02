package com.stacktrial.trial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TrialRepository extends JpaRepository<Trial, UUID> {

    List<Trial> findTop50ByOrderByCreatedAtDesc();

    List<Trial> findByStatusIn(Collection<TrialStatus> statuses);
}
