FROM ghcr.io/cirruslabs/android-sdk:36 AS builder

USER root

ARG GRADLE_VERSION=8.13

RUN wget --quiet \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -O /tmp/gradle.zip \
    && mkdir -p /opt/gradle \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && ln -s "/opt/gradle/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle \
    && rm /tmp/gradle.zip

WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app ./app

RUN gradle --no-daemon --console=plain :app:assembleDebug \
    && mkdir -p /dist \
    && cp app/build/outputs/apk/debug/app-debug.apk /dist/Ankyra-debug.apk \
    && cd /dist \
    && sha256sum Ankyra-debug.apk > Ankyra-debug.apk.sha256

FROM nginx:alpine

ENV PORT=10000

COPY render/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY render/index.html /usr/share/nginx/html/index.html
COPY --from=builder /dist/ /usr/share/nginx/html/

EXPOSE 10000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT}/health" || exit 1
