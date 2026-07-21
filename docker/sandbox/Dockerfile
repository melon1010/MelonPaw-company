FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        libreoffice \
        nodejs \
        npm \
        pandoc \
        poppler-utils \
    && rm -rf /var/lib/apt/lists/*

COPY package.json requirements-artifacts.txt preflight-artifacts.sh /opt/melonpaw-runtime/

RUN pip install --no-cache-dir -r /opt/melonpaw-runtime/requirements-artifacts.txt \
    && npm install --prefix /opt/melonpaw-runtime --omit=dev \
    && chmod +x /opt/melonpaw-runtime/preflight-artifacts.sh

ENV NODE_PATH=/opt/melonpaw-runtime/node_modules

RUN /opt/melonpaw-runtime/preflight-artifacts.sh

RUN useradd --create-home --uid 10001 melon \
    && mkdir -p /workspace \
    && chown -R melon:melon /workspace
USER melon
WORKDIR /workspace
