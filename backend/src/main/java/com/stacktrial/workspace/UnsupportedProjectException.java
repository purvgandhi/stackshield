package com.stacktrial.workspace;

/** The repository is not something StackTrial V1 can verify (not Maven, unsupported Java version, ...). */
public class UnsupportedProjectException extends RuntimeException {
    public UnsupportedProjectException(String message) {
        super(message);
    }
}
