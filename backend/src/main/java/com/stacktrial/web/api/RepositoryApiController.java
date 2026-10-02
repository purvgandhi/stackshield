package com.stacktrial.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stacktrial.architecture.ArchitectureResult;
import com.stacktrial.common.NotFoundException;
import com.stacktrial.execution.project.ProjectRun;
import com.stacktrial.execution.project.ProjectRunnerService;
import com.stacktrial.pr.PrAnalysisResult;
import com.stacktrial.pr.PrAnalyzerService;
import com.stacktrial.qa.QaResponse;
import com.stacktrial.qa.RepositoryQaService;
import com.stacktrial.repository.ImportedRepository;
import com.stacktrial.repository.RepositoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryApiController {

    private final RepositoryService repositoryService;
    private final RepositoryQaService qaService;
    private final ProjectRunnerService projectRunner;
    private final PrAnalyzerService prAnalyzer;
    private final ObjectMapper json;

    public RepositoryApiController(
            RepositoryService repositoryService,
            RepositoryQaService qaService,
            ProjectRunnerService projectRunner,
            PrAnalyzerService prAnalyzer,
            ObjectMapper json) {
        this.repositoryService = repositoryService;
        this.qaService = qaService;
        this.projectRunner = projectRunner;
        this.prAnalyzer = prAnalyzer;
        this.json = json;
    }

    @GetMapping
    public List<ImportedRepository> list() {
        return repositoryService.listAll();
    }

    @PostMapping
    public ResponseEntity<ImportedRepository> importRepository(@Valid @RequestBody ImportRepositoryRequest request) {
        ImportedRepository repo = repositoryService.importRepository(request.repoUrl(), request.ref());
        return ResponseEntity.status(HttpStatus.CREATED).body(repo);
    }

    @GetMapping("/{id}")
    public ImportedRepository get(@PathVariable UUID id) {
        return repositoryService.getById(id)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + id));
    }

    @PostMapping("/{id}/rescan")
    public ImportedRepository rescan(@PathVariable UUID id) {
        return repositoryService.rescan(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repositoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/qa")
    public QaResponse askQuestion(@PathVariable UUID id, @Valid @RequestBody RepositoryQaRequest request) {
        ImportedRepository repo = repositoryService.getById(id)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + id));
        return qaService.answerQuestion(id, request.question());
    }

    @PostMapping("/{id}/run")
    public ProjectRun executeRun(@PathVariable UUID id, @RequestBody(required = false) ProjectRunApiRequest request) {
        ImportedRepository repo = repositoryService.getById(id)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + id));

        String command = request != null ? request.command() : "mvn test";
        String image = request != null ? request.image() : null;
        Integer javaVersion = request != null ? request.javaVersion() : null;

        return projectRunner.executeCommand(id, Path.of(repo.getLocalPath()), command, image, javaVersion);
    }

    @GetMapping("/{id}/runs")
    public List<ProjectRun> getRuns(@PathVariable UUID id) {
        return projectRunner.getRuns(id);
    }

    @PostMapping("/{id}/pr-impact")
    public PrAnalysisResult analyzeImpact(@PathVariable UUID id, @RequestBody(required = false) PrImpactRequest request) {
        ImportedRepository repo = repositoryService.getById(id)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + id));

        ArchitectureResult arch = null;
        if (repo.getArchitectureResultJson() != null) {
            try {
                arch = json.readValue(repo.getArchitectureResultJson(), ArchitectureResult.class);
            } catch (Exception ignored) {
            }
        }

        String diff = request != null ? request.diff() : null;
        return prAnalyzer.analyzeImpact(Path.of(repo.getLocalPath()), arch, diff);
    }
}
