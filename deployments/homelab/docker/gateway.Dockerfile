# GPU Gateway image blueprint (T-K8S-1).
# Build context: repo root (needs the Gradle build output).
#
#   ./gradlew bootJar
#   docker build -f deployments/homelab/docker/gateway.Dockerfile \
#     -t local-registry:5000/gpu-gateway:0.1.0 .
#   docker push local-registry:5000/gpu-gateway:0.1.0
#
# Base image is mirrored into the local registry by scripts/mirror-images.sh.
# At freeze, pin the base by digest (spec §8) and rebuild.
FROM local-registry:5000/eclipse-temurin:21-jre

# bootJar output of the root project (adjust if the runnable jar moves to
# java/gpu-gateway/build/libs during T-K8S-1)
ARG JAR_FILE=build/libs/gpu-execution-plane-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/gpu-gateway.jar

# Packaged OpenAPI artifact (T-K8S-1a): copied FROM the canonical artifact,
# never independently authored (spec §4.1). CI enforces equality.
COPY java/gpu-contract/src/main/resources/openapi/openapi.yaml /app/openapi/openapi.yaml

EXPOSE 8080 8090 8091
ENTRYPOINT ["java", "-jar", "/app/gpu-gateway.jar"]
