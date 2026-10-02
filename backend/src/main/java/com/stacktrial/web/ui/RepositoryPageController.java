package com.stacktrial.web.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stacktrial.architecture.ArchitectureResult;
import com.stacktrial.common.NotFoundException;
import com.stacktrial.doctor.DoctorResult;
import com.stacktrial.execution.project.ProjectRun;
import com.stacktrial.execution.project.ProjectRunnerService;
import com.stacktrial.interview.InterviewResult;
import com.stacktrial.pr.PrAnalysisResult;
import com.stacktrial.qa.QaResponse;
import com.stacktrial.qa.RepositoryQaService;
import com.stacktrial.readiness.ReadinessResult;
import com.stacktrial.repository.ImportedRepository;
import com.stacktrial.repository.RepositoryService;
import com.stacktrial.scanner.ScanResult;
import com.stacktrial.security.SecurityResult;
import com.stacktrial.trial.TrialReportService;
import com.stacktrial.trial.TrialService;
import com.stacktrial.web.api.TrialSummary;
import com.stacktrial.workspace.change.ChangeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Controller
public class RepositoryPageController {

    private static final Logger log = LoggerFactory.getLogger(RepositoryPageController.class);

    private final RepositoryService repositoryService;
    private final RepositoryQaService qaService;
    private final ProjectRunnerService projectRunner;
    private final TrialService trials;
    private final TrialReportService reports;
    private final ObjectMapper json;

    public RepositoryPageController(
            RepositoryService repositoryService,
            RepositoryQaService qaService,
            ProjectRunnerService projectRunner,
            TrialService trials,
            TrialReportService reports,
            ObjectMapper json) {
        this.repositoryService = repositoryService;
        this.qaService = qaService;
        this.projectRunner = projectRunner;
        this.trials = trials;
        this.reports = reports;
        this.json = json;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<ImportedRepository> repos = repositoryService.listAll();
        List<TrialSummary> recentTrials = trials.recent().stream().map(TrialSummary::of).toList();

        long passCount = recentTrials.stream().filter(t -> "PASS".equalsIgnoreCase(t.verdict()) || "IMPROVEMENT".equalsIgnoreCase(t.verdict())).count();
        double passRate = recentTrials.isEmpty() ? 100.0 : ((double) passCount / recentTrials.size()) * 100.0;

        model.addAttribute("repos", repos);
        model.addAttribute("trials", recentTrials);
        model.addAttribute("totalRepos", repos.size());
        model.addAttribute("totalTrials", recentTrials.size());
        model.addAttribute("passRate", Math.round(passRate));
        model.addAttribute("activeNav", "dashboard");
        return "dashboard";
    }

    @GetMapping("/repositories")
    public String repositories(Model model) {
        List<ImportedRepository> repos = repositoryService.listAll();
        model.addAttribute("repos", repos);
        model.addAttribute("activeNav", "repositories");
        return "repositories";
    }

    @PostMapping("/repositories/import")
    public String importRepo(
            @RequestParam("repoUrl") String repoUrl,
            @RequestParam(value = "ref", required = false) String ref,
            Model model) {
        try {
            ImportedRepository repo = repositoryService.importRepository(repoUrl, ref);
            return "redirect:/repositories/" + repo.getId() + "/overview";
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("repos", repositoryService.listAll());
            model.addAttribute("activeNav", "repositories");
            return "repositories";
        }
    }

    @GetMapping("/repositories/{id}")
    public String repoDefault(@PathVariable UUID id) {
        return "redirect:/repositories/" + id + "/overview";
    }

    @GetMapping("/repositories/{id}/overview")
    public String overview(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "overview");
        return "overview";
    }

    @GetMapping("/repositories/{id}/architecture")
    public String architecture(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "architecture");
        return "architecture";
    }

    @GetMapping("/repositories/{id}/doctor")
    public String doctor(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "doctor");
        return "doctor";
    }

    @GetMapping("/repositories/{id}/run")
    public String runSandbox(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "run");
        List<ProjectRun> runs = projectRunner.getRuns(id);
        model.addAttribute("runs", runs);
        return "run";
    }

    @PostMapping("/repositories/{id}/run")
    public String executeRun(
            @PathVariable UUID id,
            @RequestParam(value = "command", required = false, defaultValue = "mvn test") String command,
            @RequestParam(value = "image", required = false) String image,
            Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        ScanResult scan = parseJson(repo.getScanResultJson(), ScanResult.class);
        Integer javaVersion = scan != null ? scan.javaVersion() : null;

        projectRunner.executeCommand(id, Path.of(repo.getLocalPath()), command, image, javaVersion);
        return "redirect:/repositories/" + id + "/run";
    }

    @GetMapping("/repositories/{id}/qa")
    public String qa(@PathVariable UUID id, @RequestParam(value = "q", required = false) String question, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "qa");

        if (question != null && !question.isBlank()) {
            QaResponse qaResponse = qaService.answerQuestion(id, question);
            model.addAttribute("qaResponse", qaResponse);
            model.addAttribute("question", question);
        }
        return "qa";
    }

    @GetMapping("/repositories/{id}/security")
    public String security(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "security");
        return "security";
    }

    @GetMapping("/repositories/{id}/pr-impact")
    public String prImpact(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "pr-impact");
        return "pr-impact";
    }

    @GetMapping("/repositories/{id}/readiness")
    public String readiness(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "readiness");
        return "readiness";
    }

    @GetMapping("/repositories/{id}/interview")
    public String interview(@PathVariable UUID id, Model model) {
        ImportedRepository repo = getRepoOrThrow(id);
        populateRepoModel(model, repo, "interview");
        return "interview";
    }

    @PostMapping("/repositories/{id}/rescan")
    public String rescan(@PathVariable UUID id) {
        repositoryService.rescan(id);
        return "redirect:/repositories/" + id + "/overview";
    }

    @PostMapping("/repositories/{id}/delete")
    public String delete(@PathVariable UUID id) {
        repositoryService.delete(id);
        return "redirect:/repositories";
    }

    @GetMapping("/trials/new")
    public String newTrial(@RequestParam(value = "repoUrl", required = false) String repoUrl, Model model) {
        TrialForm form = new TrialForm();
        if (repoUrl != null && !repoUrl.isBlank()) {
            form.setRepoUrl(repoUrl);
        }
        model.addAttribute("form", form);
        model.addAttribute("changeTypes", ChangeType.values());
        model.addAttribute("repos", repositoryService.listAll());
        model.addAttribute("activeNav", "new-trial");
        return "new-trial";
    }

    private ImportedRepository getRepoOrThrow(UUID id) {
        return repositoryService.getById(id)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + id));
    }

    private void populateRepoModel(Model model, ImportedRepository repo, String activeNav) {
        model.addAttribute("repo", repo);
        model.addAttribute("allRepos", repositoryService.listAll());
        model.addAttribute("activeNav", activeNav);

        ScanResult scan = parseJson(repo.getScanResultJson(), ScanResult.class);
        ArchitectureResult arch = parseJson(repo.getArchitectureResultJson(), ArchitectureResult.class);
        DoctorResult doc = parseJson(repo.getDoctorResultJson(), DoctorResult.class);
        SecurityResult sec = parseJson(repo.getSecurityResultJson(), SecurityResult.class);
        ReadinessResult read = parseJson(repo.getReadinessResultJson(), ReadinessResult.class);
        PrAnalysisResult imp = parseJson(repo.getImpactResultJson(), PrAnalysisResult.class);
        InterviewResult intv = parseJson(repo.getInterviewResultJson(), InterviewResult.class);

        model.addAttribute("scan", scan);
        model.addAttribute("arch", arch);
        model.addAttribute("doctor", doc);
        model.addAttribute("security", sec);
        model.addAttribute("readiness", read);
        model.addAttribute("impact", imp);
        model.addAttribute("interview", intv);
    }

    private <T> T parseJson(String jsonStr, Class<T> clazz) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }
        try {
            return json.readValue(jsonStr, clazz);
        } catch (Exception e) {
            log.debug("Could not deserialize {} JSON: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
