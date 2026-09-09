ARG KUBERNETES_VERSION=v1.34.1
ARG CONTAINERD_VERSION=2.3.5
ARG TARGETARCH

FROM alpine:3.22 AS crun

RUN apk add --no-cache crun \
	&& mkdir -p /runtime/lib /runtime/usr/lib /runtime/usr/bin \
	&& cp -L /lib/ld-musl-*.so.1 /runtime/lib/ \
	&& for library in libseccomp.so.2 libcap.so.2 libyajl.so.2 libjson-c.so.5; do \
		if [ -e "/usr/lib/${library}" ]; then cp -L "/usr/lib/${library}" /runtime/usr/lib/; fi; \
	done \
	&& cp /usr/bin/crun /runtime/usr/bin/crun

FROM debian:13-slim AS runtime

ARG CONTAINERD_VERSION
ARG TARGETARCH


RUN apt-get update \
	&& apt-get install --no-install-recommends --yes ca-certificates curl \
	&& rm -rf /var/lib/apt/lists/* \
	&& mkdir -p /runtime/usr/local/bin /runtime/etc/containerd /runtime/run/containerd \
		/runtime/var/lib/containerd /runtime/var/lib/kubelet \
		/runtime/lib64 /runtime/lib/x86_64-linux-gnu /runtime/lib/aarch64-linux-gnu \
	&& curl --fail --location --retry 3 \
		"https://github.com/containerd/containerd/releases/download/v${CONTAINERD_VERSION}/containerd-${CONTAINERD_VERSION}-linux-${TARGETARCH}.tar.gz" \
		| tar -xz -C /runtime \
	&& printf '%s\n' \
		'version = 4' \
		'' \
		'[plugins."io.containerd.cri.v1.runtime"]' \
		'' \
		'[plugins."io.containerd.cri.v1.runtime".containerd]' \
		'default_runtime_name = "crun"' \
		'' \
		'[plugins."io.containerd.cri.v1.runtime".containerd.runtimes.crun]' \
		'runtime_type = "io.containerd.runc.v2"' \
		'' \
		'[plugins."io.containerd.cri.v1.runtime".containerd.runtimes.crun.options]' \
		'BinaryName = "/usr/bin/crun"' \
		> /runtime/etc/containerd/config.toml \
	&& case "${TARGETARCH}" in \
		amd64) \
			cp /lib64/ld-linux-x86-64.so.2 /runtime/lib64/ \
			&& cp /lib/x86_64-linux-gnu/libc.so.6 /runtime/lib/x86_64-linux-gnu/;; \
		arm64) \
			cp /lib/ld-linux-aarch64.so.1 /runtime/lib/ \
			&& cp /lib/aarch64-linux-gnu/libc.so.6 /runtime/lib/aarch64-linux-gnu/;; \
		*) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1;; \
	esac

RUN cp /runtime/bin/containerd /runtime/usr/local/bin/containerd \
	&& cp /runtime/bin/containerd-shim-runc-v2 /runtime/usr/local/bin/containerd-shim-runc-v2

FROM golang:1.24-alpine AS supervisor

ARG TARGETARCH

COPY images/worker-init.go /worker-init.go

RUN CGO_ENABLED=0 GOOS=linux GOARCH="${TARGETARCH}" \
	go build -trimpath -ldflags "-s -w -extldflags=-static" \
		-o /worker-init /worker-init.go

FROM golang:1.24-alpine AS kubelet

ARG KUBERNETES_VERSION
ARG TARGETARCH

RUN apk add --no-cache ca-certificates curl musl-dev \
	&& mkdir /kubernetes \
	&& curl --fail --location --retry 3 \
		"https://github.com/kubernetes/kubernetes/archive/refs/tags/${KUBERNETES_VERSION}.tar.gz" \
		| tar -xz --strip-components=1 -C /kubernetes \
	&& cd /kubernetes \
	&& CGO_ENABLED=0 GOOS=linux GOARCH="${TARGETARCH}" \
		go build -trimpath -tags netgo,osusergo \
		-ldflags "-s -w -extldflags=-static" \
		-o /kubelet ./cmd/kubelet

FROM scratch

COPY --from=kubelet /kubelet /usr/local/bin/kubelet
COPY --from=runtime /runtime/usr/local/bin/containerd /usr/local/bin/containerd
COPY --from=runtime /runtime/usr/local/bin/containerd-shim-runc-v2 /usr/local/bin/containerd-shim-runc-v2
COPY --from=supervisor /worker-init /usr/local/bin/worker-init
COPY --from=runtime /runtime/etc/containerd/config.toml /etc/containerd/config.toml
COPY --from=runtime /runtime/run /run
COPY --from=runtime /runtime/var /var
COPY --from=runtime /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=runtime /runtime/lib64 /lib64
COPY --from=runtime /runtime/lib /lib
COPY --from=crun /runtime/lib /lib
COPY --from=crun /runtime/usr/lib /usr/lib
COPY --from=crun /runtime/usr/bin/crun /usr/bin/crun

USER 0:0
ENTRYPOINT ["/usr/local/bin/worker-init"]
