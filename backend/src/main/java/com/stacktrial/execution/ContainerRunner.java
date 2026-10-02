package com.stacktrial.execution;

/**
 * Runs one command inside a disposable, resource-limited container. The only component that talks to Docker.
 * Implementations must never run target code on the host.
 */
public interface ContainerRunner {

    ContainerRunResult run(ContainerRunSpec spec);
}
