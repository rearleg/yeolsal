FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Prime the dependency cache as a separate layer so re-builds that
# only change source code do not re-download every dependency.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version \
    && ./gradlew --no-daemon dependencies > /dev/null

# Copy sources and build the boot jar.
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

# Embed the build commit so an operator running
# `docker compose exec api cat /app/COMMIT` always sees which main
# revision is actually live, even when the image and the host
# checkout disagree. The default "unknown" preserves the legacy
# behaviour for ad-hoc local builds that don't pass the arg.
ARG GIT_SHA=unknown
RUN printf '%s\n' "${GIT_SHA}" > /app/COMMIT

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
