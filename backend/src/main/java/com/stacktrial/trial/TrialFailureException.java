package com.stacktrial.trial;

/** An expected reason for a trial to end as FAILED (bad repo, unsupported project, invalid diff, Docker down). */
class TrialFailureException extends RuntimeException {
    TrialFailureException(String message) {
        super(message);
    }
}
