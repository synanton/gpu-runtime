FROM python:3.12-slim
# stdlib-only mock — no pip installs, nothing to pin beyond the base image.
WORKDIR /app
COPY mock_provider.py .
EXPOSE 8080
CMD ["python3", "mock_provider.py"]
