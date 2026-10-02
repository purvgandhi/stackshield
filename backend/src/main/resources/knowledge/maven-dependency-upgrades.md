# Maven dependency upgrades (curated summary)
id: KB-MVN
source: https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html

## Dependency mediation and the dependency tree
When several versions of the same artifact are reachable, Maven picks the one nearest to the project in the dependency tree ("nearest definition wins"), not the newest. Upgrading one dependency can therefore change which version of a transitive library ends up on the classpath. mvn dependency:tree shows the resolved versions and is the first thing to compare between a working and a failing build.

## Managed versions and BOMs
Spring Boot manages versions for hundreds of libraries through spring-boot-dependencies (a BOM). Overriding one managed library with an explicit version on a single dependency can leave related artifacts of the same family (for example jackson-databind and jackson-core) on different versions. Overriding the BOM property (for example jackson-bom.version) keeps the family aligned.

## Binary incompatibility symptoms
A library upgrade can compile cleanly and still fail at runtime or in tests when a method or class used by another library was removed or changed. Typical errors are NoSuchMethodError, NoClassDefFoundError, ClassNotFoundException and AbstractMethodError, usually thrown from inside a third-party library rather than from project code.

## Behavioural changes after an upgrade
When an upgrade changes defaults (serialization settings, date formats, URL matching, validation rules), the build still succeeds but specific tests fail with assertion errors. Comparing which tests passed before and fail after, together with the library's release notes for the versions in between, usually identifies the changed default.
