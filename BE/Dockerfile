FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Build context is the repo root (set in infra/docker-compose.yml) so
# the Gradle validateTokens / generateTokens tasks — Story 1.5,
# Arch §4.16 — can read FE/src/theme/tokens.json as their canonical
# input. All COPY paths below are therefore prefixed with BE/.

# Prime the dependency cache as a separate layer so re-builds that
# only change source code do not re-download every dependency.
COPY BE/gradlew BE/settings.gradle BE/build.gradle ./
COPY BE/gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version \
    && ./gradlew --no-daemon dependencies > /dev/null

# Copy sources and build the boot jar.
COPY BE/src ./src

# Story 1.5 design-system tokens — validateTokens / generateTokens
# resolve `${rootDir}/../FE/src/theme/tokens.json`. With WORKDIR
# /workspace the expression evaluates to /FE/src/theme/tokens.json,
# so we COPY the canonical token file into that absolute location
# rather than wiring the path through an env var (which would also
# require a build.gradle change for one rare consumer).
COPY FE/src/theme/tokens.json /FE/src/theme/tokens.json

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
