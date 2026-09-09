ARG KUBERNETES_VERSION=v1.34.1
ARG CRIO_VERSION=1.36.5
ARG TARGETARCH

FROM alpine:3.22 AS crio
ARG CRIO_VERSION
ARG TARGETARCH

RUN apk add --no-cache ca-certificates curl tar \
	&& mkdir /crio \
	&& curl --fail --location --retry 3 \
		"https://storage.googleapis.com/cri-o/artifacts/cri-o.${TARGETARCH}.v${CRIO_VERSION}.tar.gz" \
		| tar -xz -C /crio \
	&& mkdir -p /runtime/usr/local/bin /runtime/usr/bin /runtime/usr/libexec/crio \
		/runtime/etc/crio /runtime/etc/cni/net.d /runtime/opt/cni/bin \
		/runtime/run/crio /runtime/var/lib/containers/storage \
		/runtime/var/lib/kubelet \
	&& cp /crio/cri-o/bin/crio /runtime/usr/local/bin/crio \
	&& cp /crio/cri-o/bin/conmon /runtime/usr/libexec/crio/conmon \
	&& cp /crio/cri-o/bin/pinns /runtime/usr/libexec/crio/pinns \
	&& cp /crio/cri-o/bin/crun /runtime/usr/libexec/crio/crun \
	&& cp /crio/cri-o/bin/crun /runtime/usr/bin/crun \
	&& cp -a /crio/cri-o/cni-plugins/. /runtime/opt/cni/bin/ \
	&& cp /crio/cri-o/etc/10-crio.conf /runtime/etc/crio/10-crio.conf \
	&& cp /crio/cri-o/etc/crio-umount.conf /runtime/etc/crio/crio-umount.conf \
	&& cp /crio/cri-o/contrib/policy.json /runtime/etc/crio/policy.json \
	&& printf '%s\n' \
		'[crio.image]' \
		'signature_policy = "/etc/crio/policy.json"' \
		'' \
		'[crio.runtime]' \
		'default_runtime = "crun"' \
		'runtime_path = "/usr/libexec/crio/crun"' \
		'monitor_path = "/usr/libexec/crio/conmon"' \
		'' \
		'[crio.network]' \
		'network_dir = "/etc/cni/net.d"' \
		'plugin_dirs = ["/opt/cni/bin"]' \
		> /runtime/etc/crio/crio.conf

FROM alpine:3.22 AS nsenter

RUN apk add --no-cache util-linux-misc \
	&& mkdir -p /runtime/lib /runtime/usr/bin \
	&& cp -L /lib/ld-musl-*.so.1 /runtime/lib/ \
	&& cp /usr/bin/nsenter /runtime/usr/bin/nsenter

FROM alpine:3.22 AS nftables

RUN apk add --no-cache nftables \
	&& mkdir -p /runtime/lib /runtime/usr/bin /runtime/usr/lib /runtime/usr/share \
	&& cp -L /lib/ld-musl-*.so.1 /runtime/lib/ \
	&& cp /usr/sbin/nft /runtime/usr/bin/nft \
	&& for library in \
		libnftables.so.1 libreadline.so.8 libmnl.so.0 libnftnl.so.11 \
		libjansson.so.4 libgmp.so.10 libncursesw.so.6; do \
		cp -L "/usr/lib/${library}" /runtime/usr/lib/; \
	done \
	&& if [ -d /usr/share/nftables ]; then \
		cp -a /usr/share/nftables /runtime/usr/share/; \
	fi

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
COPY --from=crio /runtime/usr/local/bin/crio /usr/local/bin/crio
COPY --from=supervisor /worker-init /usr/local/bin/worker-init
COPY --from=crio /runtime/usr/libexec/crio /usr/libexec/crio
COPY --from=crio /runtime/usr/bin/crun /usr/bin/crun
COPY --from=nsenter /runtime/lib /lib
COPY --from=nsenter /runtime/usr/bin/nsenter /usr/bin/nsenter
COPY --from=nftables /runtime/lib /lib
COPY --from=nftables /runtime/usr/bin/nft /usr/bin/nft
COPY --from=nftables /runtime/usr/lib /usr/lib
COPY --from=nftables /runtime/usr/share/nftables /usr/share/nftables
COPY --from=crio /runtime/etc/crio /etc/crio
COPY --from=crio /runtime/etc/cni /etc/cni
COPY --from=crio /runtime/opt/cni /opt/cni
COPY --from=crio /runtime/run /run
COPY --from=crio /runtime/var /var
COPY --from=crio /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

USER 0:0
ENV PATH="/usr/local/bin:/usr/bin:/usr/libexec/crio:/opt/cni/bin"
ENTRYPOINT ["/usr/local/bin/worker-init"]
