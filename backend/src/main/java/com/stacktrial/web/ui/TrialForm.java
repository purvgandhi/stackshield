package com.stacktrial.web.ui;

import com.stacktrial.workspace.change.ChangeType;

/** Mutable form-backing object for the New Trial page. */
public class TrialForm {

    private String repoUrl;
    private String baselineRef = "main";
    private ChangeType changeType = ChangeType.SPRING_BOOT_VERSION;
    private String groupId;
    private String artifactId;
    private String targetVersion;
    private String diff;

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getBaselineRef() { return baselineRef; }
    public void setBaselineRef(String baselineRef) { this.baselineRef = baselineRef; }
    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public String getDiff() { return diff; }
    public void setDiff(String diff) { this.diff = diff; }
}
