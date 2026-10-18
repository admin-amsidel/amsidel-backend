# ---- Build stage: compiles the jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# .mvn/ is not copied, so the build uses Maven Central (no corporate mirror)
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ---- Run stage: small image with just the JRE + jar ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# MaxRAMPercentage keeps Java inside small (512 MB) cloud instances
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
