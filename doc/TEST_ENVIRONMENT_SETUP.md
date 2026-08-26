# GPU Runtime Test Environment Setup

> **Document version:** 1.0  
> **Date:** 2026-08-23  
> **Status:** Test environment configuration for Phase 1-3 validation

## Overview

This document describes the test environment setup for the Synanton GPU Execution Plane using two Linux machines with NVIDIA GPUs and vLLM installed. The environment supports end-to-end testing of the ingestion pipeline through GPU-backed query execution.

## Test Infrastructure

### Hardware Configuration

| Node | Role | GPU | VRAM | vLLM Status | IP/Hostname |
|------|------|-----|------|-------------|-------------|
| node2 | Primary GPU node | NVIDIA | 16 GB | Installed | TBD |
| node3 | Secondary GPU node | NVIDIA | 16 GB | Installed | TBD |

### Software Stack

- **OS:** Linux (kernel 7.0.0-30-generic assumed)
- **GPU Runtime:** vLLM (pre-installed)
- **NVIDIA Driver:** TBD (verify with `nvidia-smi`)
- **Java:** 21 (required for Lucentrix and GPU Gateway)
- **PostgreSQL:** 14+ (execution state store)
- **Cassandra/ScyllaDB:** 4.x (ingestion cache)
- **MinIO/S3:** Content storage (synvault)

## Architecture Components

The test environment implements the following flow:

```
Local Books Library
        │
        ▼
   Lucentrix Crawler
        │
        ▼
   Synanton Platform
        │
        ├─── synvault (content store)
        ├─── synflux (ingestion router)
        ├─── ingestion-cache (Cassandra)
        ├─── syntology (ontology)
        └─── synquest (hybrid search)
        │
        ▼
    GPU Execution Plane
        │
        ├─── GPU Gateway (node2/node3)
        ├─── PostgreSQL (execution state)
        └─── vLLM Runtime (GPU inference)
```

## Phase 1: Prerequisites Verification

### 1.1 Check GPU Availability

On both node2 and node3:

```bash
# Verify NVIDIA driver
nvidia-smi

# Expected output should show:
# - Driver version
# - CUDA version
# - GPU model
# - 16 GB memory available
```

### 1.2 Check vLLM Installation

```bash
# Check vLLM installation
python3 -c "import vllm; print(vllm.__version__)"

# Verify vLLM can see GPUs
python3 -c "import torch; print(f'CUDA available: {torch.cuda.is_available()}'); print(f'GPU count: {torch.cuda.device_count()}')"
```

### 1.3 Network Connectivity

```bash
# From development machine, verify SSH access
ssh user@node2
ssh user@node3

# Check inter-node connectivity
# On node2:
ping -c 3 node3

# On node3:
ping -c 3 node2
```

## Phase 2: Data Infrastructure Setup

### 2.1 PostgreSQL (Execution State Store)

Install PostgreSQL on one of the nodes or a separate database server:

```bash
# Install PostgreSQL 14+
sudo apt-get update
sudo apt-get install postgresql-14 postgresql-client-14

# Start PostgreSQL
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Create database and user
sudo -u postgres psql <<EOF
CREATE DATABASE gpu_execution;
CREATE USER gpu_gateway WITH ENCRYPTED PASSWORD 'your_secure_password';
GRANT ALL PRIVILEGES ON DATABASE gpu_execution TO gpu_gateway;
EOF
```

### 2.2 Cassandra/ScyllaDB (Ingestion Cache)

Follow the Synanton platform setup for ingestion-cache. Reference: `/synanton/platform/README.md`

```bash
# Start Cassandra/ScyllaDB
# Create keyspace for ingestion cache
cqlsh <<EOF
CREATE KEYSPACE IF NOT EXISTS synanton_ingestion 
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
EOF
```

### 2.3 MinIO (Content Storage)

```bash
# Download and install MinIO
wget https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x minio
sudo mv minio /usr/local/bin/

# Create data directory
mkdir -p ~/minio-data

# Start MinIO
minio server ~/minio-data --console-address ":9001" &

# Access MinIO console at http://localhost:9001
# Default credentials: minioadmin / minioadmin

# Create bucket for synvault
mc alias set local http://localhost:9000 minioadmin minioadmin
mc mb local/synanton-content
```

## Phase 3: GPU Execution Plane Deployment

### 3.1 Build GPU Gateway

From the gpu-runtime repository:

```bash
cd /synanton/gpu-runtime

# Build the project (Phase 1 implementation)
./gradlew clean build -x test

# Package for deployment
./gradlew bootJar
```

### 3.2 Configure GPU Gateway

Create `application.yml` for the GPU Gateway:

```yaml
# config/application-test.yml
spring:
  application:
    name: gpu-gateway
  datasource:
    url: jdbc:postgresql://localhost:5432/gpu_execution
    username: gpu_gateway
    password: your_secure_password
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2

server:
  port: 8100

gpu:
  gateway:
    # Execution configuration
    execution:
      lease:
        timeout: 5m
        heartbeatInterval: 60s
      retry:
        maxAttempts: 3
        backoffBaseMs: 500
    
    # Model serving directory
    models:
      default:
        endpoint: http://node2:8000
        fallback: http://node3:8000
    
    # Artifact resolution
    artifacts:
      cache:
        path: /var/lib/gpu-gateway/model-cache
        staging: /var/lib/gpu-gateway/.staging
      registry:
        url: https://huggingface.co
        auth: false  # Set to true if using private models
    
    # Admission control
    admission:
      defaultConcurrency: 8  # Adjust based on GPU memory
    
    # Observability
    metrics:
      enabled: true

logging:
  level:
    org.synanton.gpu: DEBUG
    root: INFO
```

### 3.3 Deploy vLLM on GPU Nodes

On node2:

```bash
# Create vLLM configuration
cat > vllm-config.yaml <<EOF
# Model to serve (adjust based on your requirements)
model: mistralai/Mistral-7B-Instruct-v0.2
host: 0.0.0.0
port: 8000
gpu-memory-utilization: 0.9
max-model-len: 4096
EOF

# Start vLLM server
python3 -m vllm.entrypoints.openai.api_server \
  --model mistralai/Mistral-7B-Instruct-v0.2 \
  --host 0.0.0.0 \
  --port 8000 \
  --gpu-memory-utilization 0.9 \
  --max-model-len 4096 &

# Verify vLLM is running
curl http://localhost:8000/health
```

Repeat on node3 (or use a different model if testing multi-model scenarios).

### 3.4 Initialize PostgreSQL Schema

```bash
# Run schema initialization
cd /synanton/gpu-runtime

# Apply database migrations (when available)
# For now, manually create schema based on implementation plan:

psql -h localhost -U gpu_gateway -d gpu_execution <<EOF
-- Execution state table
CREATE TABLE IF NOT EXISTS executions (
    execution_id UUID PRIMARY KEY,
    request_id VARCHAR(255) NOT NULL UNIQUE,
    tenant_id VARCHAR(255) NOT NULL,
    model VARCHAR(255) NOT NULL,
    model_version VARCHAR(255) NOT NULL,
    operation VARCHAR(50) NOT NULL,
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    error_code VARCHAR(100),
    error_message TEXT,
    request_hash VARCHAR(64),
    response_data JSONB,
    execution_class VARCHAR(50),
    trace_context TEXT
);

CREATE INDEX idx_executions_request_id ON executions(request_id);
CREATE INDEX idx_executions_state ON executions(state);
CREATE INDEX idx_executions_model ON executions(model);
CREATE INDEX idx_executions_updated_at ON executions(updated_at);

-- Model capabilities table
CREATE TABLE IF NOT EXISTS model_capabilities (
    model_id VARCHAR(255) PRIMARY KEY,
    concurrency_limit INTEGER NOT NULL,
    max_batch_size INTEGER,
    supported_operations TEXT[],
    gpu_type VARCHAR(100),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Insert test model configuration
INSERT INTO model_capabilities (model_id, concurrency_limit, supported_operations, gpu_type)
VALUES ('mistralai/Mistral-7B-Instruct-v0.2', 8, ARRAY['SYNTHESIZE', 'EMBED'], 'NVIDIA-16GB')
ON CONFLICT (model_id) DO NOTHING;
EOF
```

### 3.5 Start GPU Gateway

```bash
# Create log directory
mkdir -p ~/gpu-gateway/logs

# Start GPU Gateway
cd /synanton/gpu-runtime
java -jar build/libs/gpu-gateway-*.jar \
  --spring.config.location=file:./config/application-test.yml \
  > ~/gpu-gateway/logs/gateway.log 2>&1 &

# Verify startup
tail -f ~/gpu-gateway/logs/gateway.log

# Test health endpoint (when implemented)
curl http://localhost:8100/health
```

## Phase 4: Synanton Platform Setup

### 4.1 Configure Synanton Platform

In the main platform repository at `/synanton/platform`:

```bash
cd /synanton/platform

# Update gateway configuration to enable GPU execution client
# Edit config/gateway-test.yml:
cat >> config/gateway-test.yml <<EOF
gateway:
  gpu:
    enabled: true
    endpoint: localhost:8100
    timeout-ms: 120000
    retry:
      max-attempts: 3
      backoff-base-ms: 500
EOF
```

### 4.2 Start Synanton Ingestion Stack

```bash
cd /synanton/platform

# Start required services
# synvault (content store)
java -jar synvault/target/synvault-*.jar &

# synflux (ingestion router)
java -jar synflux/target/synflux-*.jar &

# Wait for services to be ready
sleep 10
```

## Phase 5: Lucentrix Ingestion Setup

### 5.1 Build Lucentrix

```bash
cd /synanton/lucentrix

# Build Lucentrix CLI
mvn clean package -DskipTests

# Verify build
ls -lh ingestion/crawler/target/crawler-*.jar
```

### 5.2 Configure Lucentrix for Local Books

Create configuration for scanning local books library:

```bash
# Create config directory
mkdir -p config

# Create synanton target configuration
cat > config/synanton.json <<EOF
{
  "name": "synanton-test",
  "baseUrl": "http://localhost:8088",
  "synfluxUrl": "http://localhost:8090",
  "tenant": "test-library",
  "apiKey": ""
}
EOF

# Create file source configuration for local books
cat > config/books-source.json <<EOF
{
  "name": "local-books",
  "sourceType": "filesystem",
  "basePath": "/path/to/your/books/library",
  "patterns": ["*.pdf", "*.epub", "*.txt", "*.md"],
  "recursive": true,
  "maxFileSize": 52428800
}
EOF

# Update application.properties
cat > application.properties <<EOF
# Source plugin configuration
source.plugin.id=filesystem-source-plugin
source.plugin.config=config/books-source.json

# Target plugin configuration
target.plugin.id=synanton-target-plugin
target.plugin.config=config/synanton.json

# Server configuration for status UI
server.port=8095
EOF
```

### 5.3 Prepare Test Books Library

```bash
# Create test books directory if it doesn't exist
mkdir -p ~/test-books-library

# Add some test files (or point to existing library)
# Structure example:
# ~/test-books-library/
#   ├── programming/
#   │   ├── java-guide.pdf
#   │   └── python-tutorial.pdf
#   ├── literature/
#   │   ├── novel1.epub
#   │   └── essay.txt
#   └── technical/
#       └── architecture-docs.md

# Update the basePath in config/books-source.json
sed -i 's|/path/to/your/books/library|'$HOME'/test-books-library|' config/books-source.json
```

## Phase 6: End-to-End Test Execution

### 6.1 Verify All Services Are Running

```bash
# Check GPU nodes
ssh node2 "curl -s http://localhost:8000/health"
ssh node3 "curl -s http://localhost:8000/health"

# Check GPU Gateway
curl -s http://localhost:8100/health

# Check PostgreSQL
psql -h localhost -U gpu_gateway -d gpu_execution -c "SELECT COUNT(*) FROM executions;"

# Check Cassandra
cqlsh -e "DESCRIBE KEYSPACE synanton_ingestion;"

# Check MinIO
mc admin info local

# Check Synanton services
curl -s http://localhost:8088/health  # synvault
curl -s http://localhost:8090/health  # synflux
```

### 6.2 Run Lucentrix Ingestion

```bash
cd /synanton/lucentrix

# Option 1: Run with UI for monitoring
java -jar ingestion/crawler/target/crawler-*.jar

# Then open http://localhost:8095 and click "Start"

# Option 2: Run as one-shot CLI
java -jar ingestion/crawler/target/crawler-*.jar ingest --once --no-ui

# Monitor progress
tail -f logs/ingestion.log
```

### 6.3 Monitor Ingestion Progress

```bash
# Watch Lucentrix status endpoint
curl http://localhost:8095/api/ingest/status

# Watch event stream (SSE)
curl http://localhost:8095/api/ingest/events

# Check Cassandra for ingested documents
cqlsh -e "SELECT COUNT(*) FROM synanton_ingestion.documents;"

# Check MinIO for stored content
mc ls local/synanton-content/

# Check GPU Gateway execution logs
tail -f ~/gpu-gateway/logs/gateway.log

# Check PostgreSQL for GPU executions
psql -h localhost -U gpu_gateway -d gpu_execution -c \
  "SELECT execution_id, model, operation, state, created_at 
   FROM executions 
   ORDER BY created_at DESC 
   LIMIT 10;"
```

### 6.4 Verify GPU Execution

```bash
# Check GPU utilization on nodes
ssh node2 nvidia-smi

# Query execution metrics
psql -h localhost -U gpu_gateway -d gpu_execution <<EOF
-- Execution summary
SELECT state, COUNT(*) as count 
FROM executions 
GROUP BY state;

-- Recent executions
SELECT 
    execution_id, 
    request_id, 
    model, 
    operation, 
    state, 
    created_at,
    completed_at,
    EXTRACT(EPOCH FROM (completed_at - created_at)) as duration_seconds
FROM executions 
WHERE completed_at IS NOT NULL
ORDER BY created_at DESC 
LIMIT 10;

-- Error summary
SELECT error_code, COUNT(*) 
FROM executions 
WHERE error_code IS NOT NULL 
GROUP BY error_code;
EOF
```

## Phase 7: Test Data Validation

### 7.1 Query Cassandra Test Data

```bash
# Check ingestion cache
cqlsh <<EOF
USE synanton_ingestion;

-- Count documents by source
SELECT source, COUNT(*) 
FROM documents 
GROUP BY source;

-- Sample documents
SELECT document_id, title, source, created_at 
FROM documents 
LIMIT 10;

-- Check processing status
SELECT processing_state, COUNT(*) 
FROM documents 
GROUP BY processing_state;
EOF
```

### 7.2 Validate Content Storage

```bash
# List stored content in MinIO
mc ls --recursive local/synanton-content/

# Check content metadata
mc stat local/synanton-content/some-document-id
```

### 7.3 Test Query Flow (When Platform is Ready)

```bash
# Example hybrid search query (adjust based on actual API)
curl -X POST http://localhost:8088/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "machine learning algorithms",
    "tenant": "test-library",
    "includeSemanticSearch": true,
    "maxResults": 10
  }'
```

## Troubleshooting

### GPU Issues

```bash
# GPU not detected
nvidia-smi
# If fails, check NVIDIA driver installation

# vLLM not starting
python3 -m vllm.entrypoints.openai.api_server --help
# Check CUDA compatibility

# Out of GPU memory
# Reduce --gpu-memory-utilization or --max-model-len
# Or use smaller model
```

### Database Issues

```bash
# PostgreSQL connection failed
sudo systemctl status postgresql
netstat -tlnp | grep 5432

# Schema not created
psql -h localhost -U gpu_gateway -d gpu_execution -c "\dt"

# Connection pool exhausted
# Increase hikari.maximum-pool-size in application.yml
```

### Ingestion Issues

```bash
# Lucentrix not finding files
ls -la ~/test-books-library/
# Check basePath and patterns in config

# Synanton API connection failed
curl http://localhost:8088/health
curl http://localhost:8090/health

# MinIO access denied
mc admin user list local
# Verify credentials in synanton.json
```

### GPU Gateway Issues

```bash
# Gateway not starting
tail -f ~/gpu-gateway/logs/gateway.log
# Check for port conflicts, database connection

# Executions stuck in QUEUED
# Check vLLM nodes are accessible
curl http://node2:8000/health
curl http://node3:8000/health

# MODEL_NOT_READY errors
# Verify model is loaded in vLLM
# Check model_capabilities table has correct model_id
```

## Environment Variables Summary

Create an `.env` file for easy configuration:

```bash
# .env file for test environment
# GPU Nodes
export NODE2_HOST=node2
export NODE3_HOST=node3
export VLLM_PORT=8000

# GPU Gateway
export GPU_GATEWAY_PORT=8100
export GPU_DB_HOST=localhost
export GPU_DB_PORT=5432
export GPU_DB_NAME=gpu_execution
export GPU_DB_USER=gpu_gateway
export GPU_DB_PASSWORD=your_secure_password

# Synanton Platform
export SYNVAULT_PORT=8088
export SYNFLUX_PORT=8090

# Cassandra
export CASSANDRA_HOST=localhost
export CASSANDRA_PORT=9042

# MinIO
export MINIO_HOST=localhost
export MINIO_PORT=9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET=synanton-content

# Lucentrix
export LUCENTRIX_UI_PORT=8095
export BOOKS_LIBRARY_PATH=$HOME/test-books-library

# Load with:
source .env
```

## Next Steps

After completing this test environment setup:

1. **Phase 2-3 Implementation**: Complete GPU Gateway implementation per `GPU Execution Plane Implementation Plan v1.21.md`
2. **Integration Testing**: Validate end-to-end flow from ingestion to GPU-backed queries
3. **Performance Baseline**: Establish latency and throughput metrics
4. **Index Integration**: Connect test data to graph database and search index
5. **Production Preparation**: Implement mTLS, monitoring, and HA PostgreSQL per Phase 5

## References

- Main Platform Design: `https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.20.md`
- GPU Implementation Plan: `https://github.com/synanton/gpu-runtime/blob/main/doc/GPU%20Execution%20Plane%20Implementation%20Plan%20v1.21.md`
- Lucentrix ingestion: `https://github.com/synanton/lucentrix/blob/main/docs/synanton_ingestion.md`
- Synanton Platform: `https://github.com/synanton/platform`
