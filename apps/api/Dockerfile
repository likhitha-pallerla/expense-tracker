# Render has no native Java runtime -- Node, Python, Ruby, Go, Rust and Elixir
# only -- so Docker is the only way to deploy a JVM service there, and this
# file is also the only place the Java version is pinned. There is no
# .java-version or JAVA_VERSION equivalent to fall back on.

# ---- build ----------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# The wrapper and the POM are copied before the source so that the dependency
# download lands in its own layer. Source changes on every push; dependencies
# change rarely, and re-resolving them on a 0.1-CPU free instance is slow
# enough to matter.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are not run here. They need a database and the shared fixture files
# from packages/, neither of which exists in this build context -- CI runs the
# full suite on every push, which is the right place for it.
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar /build/app.jar

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Runs as a non-root user. A JVM does not need root, and this image is exposed
# to the internet.
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar
USER app

# Render's free instances have 512 MB. The JVM's default heap sizing reads the
# container limit and takes a quarter of it, which wastes most of the box;
# MaxRAMPercentage raises that while leaving room for metaspace, thread stacks
# and the JVM itself. Without it, this either under-uses the instance or gets
# OOM-killed partway through Flyway.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# Documentation only -- Render assigns the real port through $PORT, which
# application.yml reads. EXPOSE does not publish anything.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
