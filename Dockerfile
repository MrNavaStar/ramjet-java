FROM debian:13 AS musl-builder

FROM debian:13 AS musl-builder

ARG CMAKE_VERSION=4.2.0

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    build-essential \
    direnv \
    git \
    musl-tools \
    pkg-config \
    curl \
    zip \
    unzip \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL \
    "https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-x86_64.tar.gz" \
    -o /tmp/cmake.tar.gz \
    && tar -xzf /tmp/cmake.tar.gz -C /opt \
    && ln -s "/opt/cmake-${CMAKE_VERSION}-linux-x86_64/bin/"* /usr/local/bin/ \
    && rm /tmp/cmake.tar.gz

WORKDIR /app
COPY system system
RUN cd system && direnv allow &&  make clean && make build

FROM ghcr.io/graalvm/native-image-community:25-muslib AS builder

RUN mkdir -p /static && cd /static && curl -O https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox

WORKDIR /app
COPY common common
COPY server server
COPY gradle gradle
COPY .gitmodules .gitmodules
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
COPY gradlew gradlew

RUN ./gradlew :server:nativeCompile --no-daemon

FROM scratch

ENV GIT_CONFIG_NOSYSTEM=true

COPY --from=builder /static /static

COPY --from=musl-builder \
    /app/system/build/system \
    /static/inlet

COPY --from=builder \
    /app/server/build/native/nativeCompile/server \
    /bin/server

ENTRYPOINT ["/bin/server"]