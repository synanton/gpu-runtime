# GPU Gateway image (T-K8S-1). Build context: repo root, after `./gradlew bootJar`.
#
#   ./gradlew :java:gpu-gateway:bootJar
#   docker build -f deployments/homelab/docker/gateway.Dockerfile \
#     -t local-registry:5000/gpu-gateway:0.1.0 .
#   docker push local-registry:5000/gpu-gateway:0.1.0
#
# GPU-5 (homelab) uses the mirrored base from local-registry:5000 (scripts/mirror-images.sh).
# GPU-7 (compose) builds with the public base:  --build-arg BASE_IMAGE=eclipse-temurin:21-jre
# At freeze, pin the base by digest (Deployment Plan §8) and rebuild.
ARG BASE_IMAGE=local-registry:5000/eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

# bootJar output of the gateway module (the root project has no runnable jar)
ARG JAR_FILE=java/gpu-gateway/build/libs/gpu-gateway-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/gpu-gateway.jar

# The contract is the protobuf in java/gpu-contract (compiled into the jar);
# there is no OpenAPI artifact (Deployment Plan v3.0.0 §4.1, §15).

# 9090 gRPC synanton.gpu.v1 (the API) · 8091 actuator health/metrics
EXPOSE 9090 8091
ENTRYPOINT ["java", "-jar", "/app/gpu-gateway.jar"]
