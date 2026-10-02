# Spring Boot 3.1 to 3.4 Release Notes (curated summary)
id: KB-SB3X
source: https://github.com/spring-projects/spring-boot/wiki

## Spring Boot 3.1
Spring Boot 3.1 adds Testcontainers service connections (@ServiceConnection) and Docker Compose support (spring-boot-docker-compose). It upgrades to Hibernate 6.2. Dependency management for Apache HttpClient 4 was removed, so a project that still uses HttpClient 4 must declare its version explicitly or the build fails to resolve it.

## Spring Boot 3.2 and Spring Framework 6.1
Spring Boot 3.2 supports Java 21 and virtual threads (spring.threads.virtual.enabled=true, Java 21 required). It introduces RestClient and JdbcClient. Spring Framework 6.1 no longer infers method parameter names from bytecode debug information; code must be compiled with the -parameters flag. Projects that use spring-boot-starter-parent get this flag automatically, but builds that configure the compiler plugin themselves can fail at runtime with "Name for argument of type [...] not specified, and parameter name information not available via reflection. Ensure that the compiler uses the '-parameters' flag."

## Spring Boot 3.3
Spring Boot 3.3 adds Class Data Sharing (CDS) support for faster startup and continues the 3.x dependency upgrades. As with every minor release, managed dependency versions (Micrometer, Hibernate, Jackson and others) change, which can alter behaviour in tests even when the code still compiles.

## Spring Boot 3.4 and Spring Framework 6.2
Spring Boot 3.4 adds structured logging (ECS, Logstash, GELF formats). Spring Framework 6.2 deprecates Spring Boot's @MockBean and @SpyBean in favour of @MockitoBean and @MockitoSpyBean from spring-test. Deprecation produces compiler warnings, which fail the build only when warnings are treated as errors.

## Reading a minor-version upgrade
A Spring Boot minor upgrade changes the versions of many managed libraries at once through spring-boot-dependencies. When tests fail after such an upgrade, compare the resolved dependency trees of both versions to see which libraries moved, then check those libraries' release notes for changed defaults.
