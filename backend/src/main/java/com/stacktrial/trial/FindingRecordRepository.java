package com.stacktrial.trial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FindingRecordRepository extends JpaRepository<FindingRecord, UUID> {

    List<FindingRecord> findByTrialIdOrderByPositionAsc(UUID trialId);
}
