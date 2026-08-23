FROM ghcr.io/graalvm/native-image-community:25-muslib AS builder

RUN mkdir -p /static && cd /static && curl -O https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox

WORKDIR /app
COPY . .

RUN ./gradlew :inlet:nativeCompile --no-daemon
RUN ./gradlew :server:nativeCompile --no-daemon

FROM scratch

ENV GIT_CONFIG_NOSYSTEM=true

COPY --from=builder /static /static

COPY --from=builder \
    /app/server/build/native/nativeCompile/server \
    /bin/server

COPY --from=builder \
    /app/inlet/build/native/nativeCompile/inlet \
    /static/inlet

ENTRYPOINT ["/bin/server"]