package com.stacktrial.evidence;

/** A javac error line from the Maven build log, e.g. {@code [ERROR] /workspace/src/A.java:[12,5] cannot find symbol}. */
public record CompileError(String file, int line, int column, String message) {

    public String location() {
        return file + ":" + line + ":" + column;
    }
}
