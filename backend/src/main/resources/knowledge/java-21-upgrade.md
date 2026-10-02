# Upgrading a Java project to Java 21 (curated summary)
id: KB-JDK21
source: https://docs.oracle.com/en/java/javase/21/migrate/

## Tooling must understand class file version 65
Java 21 produces class files with version 65. Bytecode tools that read or generate classes must support it. Older Byte Buddy versions (used by Mockito) fail with "Java 21 (65) is not supported by the current version of Byte Buddy which officially supports Java 20 (64)". Older JaCoCo agents fail with "Unsupported class file major version 65"; JaCoCo 0.8.11 is the first release with Java 21 support. Upgrading these test-time libraries usually fixes the failures.

## Lombok and annotation processors
Lombok versions before 1.18.30 do not work with the Java 21 compiler and fail during compilation with errors such as "java.lang.NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'com.sun.tools.javac.tree.JCTree qualid'". Other annotation processors that use internal javac APIs can fail the same way.

## Spring Boot and Java 21
Spring Boot 3.2 is the first Spring Boot line with first-class Java 21 support, including virtual threads. Older Spring Boot lines may manage library versions (such as Mockito and Byte Buddy) that predate Java 21, so a Java upgrade on an old Spring Boot version can fail in tests even though the application code compiles.

## Removed and changed APIs
Thread.stop() throws UnsupportedOperationException since Java 20. Finalization and the Security Manager are deprecated for removal. Java 21 adds sequenced collections (getFirst, getLast, reversed on List, Deque and others); a custom collection class that already declares a method with one of these names but a different return type fails to compile.

## Dynamic agent loading warnings
Java 21 prints a warning when a Java agent is loaded dynamically into a running JVM (JEP 451). Mockito's inline mock maker attaches itself this way, so test logs show "A Java agent has been loaded dynamically". This is a warning, not a failure, but builds that treat warnings as errors can be affected.
