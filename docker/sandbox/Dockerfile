FROM node:22-bookworm-slim AS node-runtime

FROM python:3.12-slim-bookworm

COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=node-runtime /usr/local/lib/node_modules /usr/local/lib/node_modules

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        coreutils \
        curl \
        file \
        findutils \
        git \
        grep \
        jq \
        sed \
        tar \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && pip install --no-cache-dir uv==0.6.5

COPY preflight-runtime.sh /opt/melonpaw-runtime/preflight-runtime.sh

ENV HOME=/home/melon \
    XDG_CACHE_HOME=/home/melon/.cache \
    npm_config_cache=/home/melon/.cache/npm \
    npm_config_prefix=/home/melon/.npm-global \
    npm_config_update_notifier=false \
    npm_config_fund=false \
    npm_config_audit=false \
    UV_CACHE_DIR=/home/melon/.cache/uv \
    UV_NO_PROGRESS=1 \
    PYTHONUSERBASE=/home/melon/.local \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PATH=/home/melon/.local/bin:/home/melon/.npm-global/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin

RUN useradd --create-home --uid 10001 melon \
    && mkdir -p /workspace \
    && chown -R melon:melon /workspace \
    && chmod +x /opt/melonpaw-runtime/preflight-runtime.sh
USER melon
WORKDIR /workspace

RUN /opt/melonpaw-runtime/preflight-runtime.sh
