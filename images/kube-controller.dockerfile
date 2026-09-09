ARG KUBERNETES_VERSION=v1.34.1
ARG TARGETARCH

FROM golang:1.24-alpine AS kube-controller-manager

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
		-o /kube-controller-manager ./cmd/kube-controller-manager

FROM scratch

COPY --from=kube-controller-manager /kube-controller-manager /usr/local/bin/kube-controller-manager

USER 65532:65532
ENTRYPOINT ["/usr/local/bin/kube-controller-manager"]
