FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        nodejs \
        npm \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 melon \
    && mkdir -p /workspace \
    && chown -R melon:melon /workspace
USER melon
WORKDIR /workspace
