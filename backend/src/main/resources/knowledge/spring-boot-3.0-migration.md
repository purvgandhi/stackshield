# Spring Boot 3.0 Migration Guide (curated summary)
id: KB-SB30
source: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide

## Java 17 baseline
Spring Boot 3.0 requires Java 17 or later. A project that upgrades from Spring Boot 2.7 to 3.0 while still compiling for Java 8 or Java 11 fails at build time, because Spring Framework 6 and Spring Boot 3 classes are compiled for Java 17 (class file version 61). Typical symptoms are "class file has wrong version 61.0, should be 55.0" or "release version 17 not supported" from the compiler.

## Jakarta EE 9+ namespace (javax to jakarta)
Spring Boot 3.0 moves from Java EE to Jakarta EE 9/10 APIs. Imports such as javax.servlet, javax.persistence, javax.validation and javax.annotation (for example javax.annotation.PostConstruct) must change to jakarta.servlet, jakarta.persistence, jakarta.validation and jakarta.annotation. Code that still uses the javax packages fails to compile with errors like "package javax.persistence does not exist" or "cannot find symbol". Third-party libraries must also be versions built for Jakarta EE 9+.

## Spring MVC and WebFlux: trailing slash matching disabled
Spring Framework 6.0 (used by Spring Boot 3.0) no longer matches URLs with a trailing slash by default. In Spring Boot 2.x, a controller mapped to "/api/orders" also answered "/api/orders/". After the upgrade, a request to "/api/orders/" returns 404 Not Found unless the trailing-slash path is mapped explicitly. Tests that call endpoints with a trailing slash start failing with "Status expected:<200> but was:<404>". The old behaviour can be re-enabled with PathMatchConfigurer#setUseTrailingSlashMatch(true), which is deprecated; the recommended fix is to map or redirect the trailing-slash URLs explicitly.

## Configuration property changes
Many configuration properties were renamed or removed in Spring Boot 3.0, for example spring.redis.* moved to spring.data.redis.*. Old keys are silently ignored, which can change runtime behaviour without a compile error. Adding the spring-boot-properties-migrator dependency temporarily prints the renamed keys at startup.

## Spring Security 6
Spring Boot 3.0 uses Spring Security 6.0. WebSecurityConfigurerAdapter was removed; security is configured with a SecurityFilterChain bean. authorizeRequests() is replaced by authorizeHttpRequests(), and antMatchers()/mvcMatchers() by requestMatchers(). Code relying on the removed APIs fails to compile; changed defaults can make previously permitted requests return 401 or 403.

## Hibernate 6.1
Spring Boot 3.0 upgrades to Hibernate 6.1. The Maven groupId changed to org.hibernate.orm. Some HQL/JPQL queries, custom types and ID generation defaults behave differently, which can surface as query exceptions or failing repository tests after the upgrade.

## Auto-configuration registration
Auto-configuration classes must be listed in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports. Registration through the EnableAutoConfiguration key in META-INF/spring.factories is no longer supported, so custom auto-configurations registered the old way are silently not applied.

## Observability, HTTP clients and logging
Spring Cloud Sleuth is replaced by Micrometer Tracing, and the actuator httptrace endpoint is renamed httpexchanges. RestTemplate no longer supports Apache HttpClient 4; it uses Apache HttpClient 5 (org.apache.httpcomponents.client5). The default log date format changed to ISO-8601, which can break log parsers and tests that assert on log lines.
