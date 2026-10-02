package com.stacktrial.trial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * Worker threads live in memory. If the application stops mid-trial, those trials would stay RUNNING forever,
 * so at startup every unfinished trial is marked FAILED with an explicit reason.
 */
@Component
public class TrialRecovery {

    private static final Logger log = LoggerFactory.getLogger(TrialRecovery.class);

    private final TrialRepository trials;
    private final TrialStore store;

    public TrialRecovery(TrialRepository trials, TrialStore store) {
        this.trials = trials;
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedTrials() {
        var unfinished = trials.findByStatusIn(EnumSet.complementOf(EnumSet.of(TrialStatus.COMPLETED, TrialStatus.FAILED)));
        for (Trial t : unfinished) {
            store.fail(t.getId(), "Interrupted: StackTrial restarted while this trial was " + t.getStatus());
        }
        if (!unfinished.isEmpty()) {
            log.warn("Marked {} interrupted trial(s) as FAILED", unfinished.size());
        }
    }
}
