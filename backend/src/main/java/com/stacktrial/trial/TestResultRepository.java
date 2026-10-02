package com.stacktrial.trial;

import com.stacktrial.evidence.TestOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    List<TestResult> findByRunIdAndOutcomeInOrderByClassNameAscTestNameAsc(UUID runId, Collection<TestOutcome> outcomes);
}
