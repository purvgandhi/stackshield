package com.stacktrial.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stacktrial.architecture.ArchitectureExtractorService;
import com.stacktrial.architecture.ArchitectureResult;
import com.stacktrial.common.StackTrialProperties;
import com.stacktrial.doctor.DoctorResult;
import com.stacktrial.doctor.EnvironmentDoctorService;
import com.stacktrial.interview.InterviewResult;
import com.stacktrial.interview.InterviewService;
import com.stacktrial.pr.PrAnalysisResult;
import com.stacktrial.pr.PrAnalyzerService;
import com.stacktrial.qa.RepositoryQaService;
import com.stacktrial.readiness.DeploymentReadinessService;
import com.stacktrial.readiness.ReadinessResult;
import com.stacktrial.scanner.RepositoryScannerService;
import com.stacktrial.scanner.ScanResult;
import com.stacktrial.security.SecurityResult;
import com.stacktrial.security.SecurityScannerService;
import com.stacktrial.workspace.GitClient;
import com.stacktrial.workspace.RepositoryUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);

    private final ImportedRepositoryRepository repoRepository;
    private final GitClient git;
    private final RepositoryUrlValidator validator;
    private final RepositoryScannerService scanner;
    private final ArchitectureExtractorService architectureExtractor;
    private final EnvironmentDoctorService doctor;
    private final SecurityScannerService securityScanner;
    private final DeploymentReadinessService readinessService;
    private final PrAnalyzerService prAnalyzer;
    private final InterviewService interviewService;
    private final RepositoryQaService qaService;
    private final ObjectMapper json;
    private final Path storageRoot;
    private final Duration cloneTimeout;

    public RepositoryService(
            ImportedRepositoryRepository repoRepository,
            GitClient git,
            RepositoryUrlValidator validator,
            RepositoryScannerService scanner,
            ArchitectureExtractorService architectureExtractor,
            EnvironmentDoctorService doctor,
            SecurityScannerService securityScanner,
            DeploymentReadinessService readinessService,
            PrAnalyzerService prAnalyzer,
            InterviewService interviewService,
            RepositoryQaService qaService,
            ObjectMapper json,
            StackTrialProperties properties) {
        this.repoRepository = repoRepository;
        this.git = git;
        this.validator = validator;
        this.scanner = scanner;
        this.architectureExtractor = architectureExtractor;
        this.doctor = doctor;
        this.securityScanner = securityScanner;
        this.readinessService = readinessService;
        this.prAnalyzer = prAnalyzer;
        this.interviewService = interviewService;
        this.qaService = qaService;
        this.json = json;
        this.storageRoot = properties.dataDir().resolve("repositories").toAbsolutePath();
        this.cloneTimeout = properties.workspace().cloneTimeout();
    }

    public ImportedRepository importRepository(String repoUrl, String ref) {
        String cleanUrl = repoUrl.strip();
        String cleanRef = (ref == null || ref.isBlank()) ? "main" : ref.strip();
        validator.validate(cleanUrl, cleanRef);

        String repoName = extractRepoName(cleanUrl);
        UUID id = UUID.randomUUID();
        Path repoPath = storageRoot.resolve(id.toString());

        ImportedRepository repo = new ImportedRepository(
                id,
                repoName,
                cleanUrl,
                cleanRef,
                repoPath.toString(),
                RepositoryStatus.IMPORTING,
                "Cloning repository from " + cleanUrl + " (ref: " + cleanRef + ")...",
                Instant.now(),
                Instant.now()
        );
        repoRepository.save(repo);

        try {
            Files.createDirectories(repoPath);
            log.info("Cloning repo {} to {}", cleanUrl, repoPath);
            git.cloneRepository(cleanUrl, repoPath, cloneTimeout);

            try {
                git.checkout(repoPath, cleanRef);
            } catch (Exception e) {
                // If main fails, try master or HEAD
                log.warn("Failed checking out {}: {}, trying master/HEAD", cleanRef, e.getMessage());
                try {
                    git.checkout(repoPath, "master");
                    repo.setDefaultRef("master");
                } catch (Exception ex) {
                    git.checkout(repoPath, "HEAD");
                }
            }

            repo.setStatus(RepositoryStatus.ANALYZING);
            repo.setStatusMessage("Scanning files, architecture, security, and doctor diagnostics...");
            repo.setUpdatedAt(Instant.now());
            repoRepository.save(repo);

            analyzeAndIndex(repo, repoPath);

            repo.setStatus(RepositoryStatus.READY);
            repo.setStatusMessage("Repository fully analyzed and indexed.");
            repo.setUpdatedAt(Instant.now());
            return repoRepository.save(repo);

        } catch (Exception e) {
            log.error("Failed importing repository {}: {}", cleanUrl, e.getMessage(), e);
            repo.setStatus(RepositoryStatus.FAILED);
            repo.setStatusMessage("Import failed: " + e.getMessage());
            repo.setUpdatedAt(Instant.now());
            return repoRepository.save(repo);
        }
    }

    public ImportedRepository rescan(UUID id) {
        ImportedRepository repo = repoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + id));

        Path repoPath = Path.of(repo.getLocalPath());
        if (!Files.exists(repoPath)) {
            throw new IllegalStateException("Local repository path does not exist on disk: " + repoPath);
        }

        repo.setStatus(RepositoryStatus.ANALYZING);
        repo.setStatusMessage("Rescanning repository...");
        repo.setUpdatedAt(Instant.now());
        repoRepository.save(repo);

        analyzeAndIndex(repo, repoPath);

        repo.setStatus(RepositoryStatus.READY);
        repo.setStatusMessage("Repository rescanned and re-indexed successfully.");
        repo.setUpdatedAt(Instant.now());
        return repoRepository.save(repo);
    }

    private void analyzeAndIndex(ImportedRepository repo, Path repoPath) {
        // 1. Scanner
        ScanResult scanResult = scanner.scan(repoPath);
        repo.setScanResultJson(toJson(scanResult));

        // 2. Architecture
        ArchitectureResult archResult = architectureExtractor.extract(repoPath);
        repo.setArchitectureResultJson(toJson(archResult));

        // 3. Doctor
        DoctorResult docResult = doctor.diagnose(repoPath, scanResult);
        repo.setDoctorResultJson(toJson(docResult));

        // 4. Security
        SecurityResult secResult = securityScanner.scanRepository(repoPath);
        repo.setSecurityResultJson(toJson(secResult));

        // 5. Readiness
        ReadinessResult readResult = readinessService.evaluate(repoPath, scanResult);
        repo.setReadinessResultJson(toJson(readResult));

        // 6. Impact / PR
        PrAnalysisResult impResult = prAnalyzer.analyzeImpact(repoPath, archResult, null);
        repo.setImpactResultJson(toJson(impResult));

        // 7. Interview
        InterviewResult intResult = interviewService.generateQuestions(scanResult, archResult);
        repo.setInterviewResultJson(toJson(intResult));

        // 8. Q&A Indexing
        try {
            qaService.indexRepository(repo.getId(), repoPath);
        } catch (Exception e) {
            log.warn("Failed indexing code chunks for Q&A: {}", e.getMessage());
        }
    }

    public List<ImportedRepository> listAll() {
        return repoRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<ImportedRepository> getById(UUID id) {
        return repoRepository.findById(id);
    }

    public void delete(UUID id) {
        Optional<ImportedRepository> opt = repoRepository.findById(id);
        if (opt.isPresent()) {
            ImportedRepository repo = opt.get();
            try {
                FileSystemUtils.deleteRecursively(Path.of(repo.getLocalPath()));
            } catch (IOException e) {
                log.warn("Could not delete directory {}: {}", repo.getLocalPath(), e.getMessage());
            }
            qaService.indexRepository(id, Path.of("")); // cleans up chunks
            repoRepository.deleteById(id);
        }
    }

    private String toJson(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization error: {}", e.getMessage());
            return "{}";
        }
    }

    private String extractRepoName(String url) {
        String clean = url.replaceAll("\\.git$", "").replaceAll("/+$", "");
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < clean.length() - 1) {
            return clean.substring(lastSlash + 1);
        }
        return "repository-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
