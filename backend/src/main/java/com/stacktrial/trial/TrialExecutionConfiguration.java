package com.stacktrial.trial;

import com.stacktrial.common.StackTrialProperties;
import com.stacktrial.comparison.ComparisonEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TrialExecutionConfiguration {

    /**
     * Bounded worker pool for trials. Trials take minutes, so HTTP requests only enqueue work here and return 202.
     * A small pool also bounds how many build containers run at once on this machine.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor trialExecutor(StackTrialProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.execution().maxConcurrentTrials());
        executor.setMaxPoolSize(properties.execution().maxConcurrentTrials());
        executor.setQueueCapacity(properties.execution().queueCapacity());
        executor.setThreadNamePrefix("trial-worker-");
        executor.initialize();
        return executor;
    }

    /** The comparison engine is plain Java; Spring only hands out the single instance. */
    @Bean
    public ComparisonEngine comparisonEngine() {
        return new ComparisonEngine();
    }
}
