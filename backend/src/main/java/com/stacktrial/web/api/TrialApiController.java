package com.stacktrial.web.api;

import com.stacktrial.comparison.Side;
import com.stacktrial.common.NotFoundException;
import com.stacktrial.trial.Trial;
import com.stacktrial.trial.TrialReport;
import com.stacktrial.trial.TrialReportService;
import com.stacktrial.trial.TrialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/trials")
public class TrialApiController {

    private final TrialService trials;
    private final TrialReportService reports;

    public TrialApiController(TrialService trials, TrialReportService reports) {
        this.trials = trials;
        this.reports = reports;
    }

    /** Queues a trial and returns immediately: the Docker runs take minutes. Poll GET /api/trials/{id}. */
    @PostMapping
    public ResponseEntity<TrialCreatedResponse> create(@Valid @RequestBody CreateTrialRequest request) {
        Trial trial = trials.create(request.repoUrl(), request.baselineRef(), request.toChange());
        String reportUrl = "/api/trials/" + trial.getId();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(reportUrl))
                .body(new TrialCreatedResponse(trial.getId(), trial.getStatus().name(), reportUrl, "/trials/" + trial.getId()));
    }

    @GetMapping
    public List<TrialSummary> list() {
        return trials.recent().stream().map(TrialSummary::of).toList();
    }

    @GetMapping("/{id}")
    public TrialReport get(@PathVariable UUID id) {
        return reports.report(id);
    }

    @GetMapping(value = "/{id}/runs/{side}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public String buildLog(@PathVariable UUID id, @PathVariable Side side) {
        return reports.artifact(id, side, "log").orElseThrow(() -> new NotFoundException("No build log stored"));
    }

    @GetMapping(value = "/{id}/runs/{side}/dependency-tree", produces = MediaType.TEXT_PLAIN_VALUE)
    public String dependencyTree(@PathVariable UUID id, @PathVariable Side side) {
        return reports.artifact(id, side, "dependency-tree").orElseThrow(() -> new NotFoundException("No dependency tree stored"));
    }
}
