// gpu-gateway: GPU Execution Plane service.
// Implements synanton.gpu.v1.GpuExecutionService + GpuCapacityService as a gRPC server.
// Depends on gpu-contract for generated stubs.
// MUST NOT depend on synanton platform internals or any infrastructure except PostgreSQL.

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    java
}

dependencies {
    implementation(project(":java:gpu-contract"))

    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.prometheus)
    implementation(libs.micrometer.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    compileOnly(libs.javax.annotation)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.testing)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.h2)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.test {
    useJUnitPlatform()

    // Docker Engine 25+ rejects docker-java's default API (1.32). Pin 1.44 as a floor.
    // Existing DOCKER_HOST / TESTCONTAINERS_* from the environment are inherited.
    systemProperty("api.version", "1.44")
    systemProperty("docker.api.version", "1.44")
    environment("DOCKER_API_VERSION", "1.44")
}
