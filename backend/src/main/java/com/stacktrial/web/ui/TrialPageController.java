package com.stacktrial.web.ui;

import com.stacktrial.trial.Trial;
import com.stacktrial.trial.TrialReportService;
import com.stacktrial.trial.TrialService;
import com.stacktrial.web.api.TrialSummary;
import com.stacktrial.workspace.change.ChangeRequest;
import com.stacktrial.workspace.change.ChangeType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

/** Server-rendered pages: Dashboard, New Trial, Trial Progress, Trial Report. */
@Controller
public class TrialPageController {

    private final TrialService trials;
    private final TrialReportService reports;

    public TrialPageController(TrialService trials, TrialReportService reports) {
        this.trials = trials;
        this.reports = reports;
    }

    // Trial creation, progress, and report are handled below


    @PostMapping("/trials")
    public String create(@ModelAttribute("form") TrialForm form, Model model) {
        try {
            ChangeRequest change = new ChangeRequest(form.getChangeType(), blankToNull(form.getGroupId()),
                    blankToNull(form.getArtifactId()), blankToNull(form.getTargetVersion()), blankToNull(form.getDiff()));
            Trial trial = trials.create(form.getRepoUrl(), form.getBaselineRef(), change);
            return "redirect:/trials/" + trial.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("changeTypes", ChangeType.values());
            return "new-trial";
        }
    }

    /** Shows live progress while the trial runs (auto-refresh) and the full report once it is finished. */
    @GetMapping("/trials/{id}")
    public String trial(@PathVariable UUID id, Model model) {
        var report = reports.report(id);
        model.addAttribute("r", report);
        return report.finished() ? "report" : "progress";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
