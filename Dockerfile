FROM debian:13 AS musl-builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    build-essential \
    musl-tools \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | \
    sh -s -- -y --profile minimal \
    && . "$HOME/.cargo/env" \
    && rustup target add x86_64-unknown-linux-musl

WORKDIR /app
COPY system-rust system-rust
RUN . "$HOME/.cargo/env" && \
    cd system-rust && \
    cargo build --release

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
    /app/system-rust/target/x86_64-unknown-linux-musl/release/init \
    /static/inlet

COPY --from=builder \
    /app/server/build/native/nativeCompile/server \
    /bin/server

ENTRYPOINT ["/bin/server"]