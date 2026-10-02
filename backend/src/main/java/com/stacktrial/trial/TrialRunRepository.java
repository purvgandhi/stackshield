package com.stacktrial.trial;

import com.stacktrial.comparison.Side;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrialRunRepository extends JpaRepository<TrialRun, UUID> {

    List<TrialRun> findByTrialId(UUID trialId);

    Optional<TrialRun> findByTrialIdAndSide(UUID trialId, Side side);
}
