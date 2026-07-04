FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /app
COPY . .

RUN ./gradlew :server:nativeCompile --no-daemon
RUN ./gradlew :inlet:nativeCompile --no-daemon

FROM gcr.io/distroless/base-debian12

COPY --from=builder \
    /app/server/build/native/nativeCompile/server \
    /bin/server

COPY --from=builder \
    /app/inlet/build/native/nativeCompile/inlet \
    /static/inlet

ENTRYPOINT ["/bin/server"]