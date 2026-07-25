# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY melon-plugin-api/pom.xml melon-plugin-api/pom.xml
COPY melon-core/pom.xml melon-core/pom.xml
COPY melon-tools/pom.xml melon-tools/pom.xml
COPY melon-channels/pom.xml melon-channels/pom.xml
COPY melon-app/pom.xml melon-app/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl melon-app -am dependency:go-offline

COPY melon-plugin-api/src melon-plugin-api/src
COPY melon-core/src melon-core/src
COPY melon-tools/src melon-tools/src
COPY melon-channels/src melon-channels/src
COPY melon-app/src melon-app/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl melon-app -am -DskipTests package

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
COPY --from=build /workspace/melon-app/target/melonpaw-company-web.jar /app/melonpaw-company.jar
COPY docker/app-entrypoint.sh /usr/local/bin/melonpaw-entrypoint

RUN chmod 0755 /usr/local/bin/melonpaw-entrypoint

ENV SPRING_PROFILES_ACTIVE=container \
    MELON_HOME=/data \
    MELON_WORKSPACE_ROOT=/data/workspaces

WORKDIR /app
EXPOSE 8088

ENTRYPOINT ["/usr/local/bin/melonpaw-entrypoint"]
CMD ["java", "-jar", "/app/melonpaw-company.jar"]

