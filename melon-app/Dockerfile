FROM docker:27-cli AS docker-cli

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        passwd \
        tini \
        util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 melon \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/melon melon \
    && mkdir -p /app /data \
    && chown -R melon:melon /app /data

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
# JAR 由宿主机或 CI 先行构建，镜像构建不访问 Maven 仓库。
COPY melon-app/target/melonpaw-company-web.jar /app/melonpaw-company.jar
COPY docker/app-entrypoint.sh /usr/local/bin/melonpaw-entrypoint

RUN chmod 0755 /usr/local/bin/melonpaw-entrypoint

ENV SPRING_PROFILES_ACTIVE=container \
    MELON_HOME=/data \
    MELON_WORKSPACE_ROOT=/data/workspaces

WORKDIR /app
EXPOSE 8088

ENTRYPOINT ["/usr/local/bin/melonpaw-entrypoint"]
CMD ["java", "-jar", "/app/melonpaw-company.jar"]
