# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production JRE Image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/stacktrial-0.1.0-SNAPSHOT.jar app.jar

ENV SERVER_PORT=8081
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
