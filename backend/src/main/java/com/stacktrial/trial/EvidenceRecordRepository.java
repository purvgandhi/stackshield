package com.stacktrial.trial;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceRecordRepository extends JpaRepository<EvidenceRecord, UUID> {

    List<EvidenceRecord> findByTrialIdOrderByCodeAsc(UUID trialId);
}
