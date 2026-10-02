package com.stacktrial.execution;

/** Docker itself failed (daemon unreachable, image pull failed, ...). Not a build failure of the target project. */
public class ContainerExecutionException extends RuntimeException {
    public ContainerExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
