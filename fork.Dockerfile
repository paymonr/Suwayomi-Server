# Stock Suwayomi image v2.4.2366 (pinned by digest); only the server jar is replaced with this fork's.
FROM ghcr.io/suwayomi/tachidesk:v2.4.2366@sha256:1fac27c387dc2ea9949e1a01ad7f6d6de9d72d3988338a95476748e5c45c4582
ARG JAR
COPY --chown=1000:1000 --chmod=0755 ${JAR} /home/suwayomi/startup/tachidesk_latest.jar
LABEL org.opencontainers.image.source="https://github.com/paymonr/Suwayomi-Server"
