package com.synanton.gpu;

import com.synanton.gpu.config.GpuGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** GPU Execution Plane — standalone Spring Boot service implementing synanton.gpu.v1. */
@SpringBootApplication
@EnableConfigurationProperties(GpuGatewayProperties.class)
public class GpuGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpuGatewayApplication.class, args);
    }
}
