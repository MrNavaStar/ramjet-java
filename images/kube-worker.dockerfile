ARG KUBERNETES_VERSION=v1.34.1
ARG CRIO_VERSION=1.36.5
ARG TARGETARCH

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

FROM packages/kubernetes AS kube
FROM packages/crio AS crio
FROM packages/util-linux AS util-linux
FROM scratch

COPY --from=kube /kubelet /usr/local/bin/kubelet

COPY --from=crio /root /
COPY --from=crio /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

COPY --from=util-linux /sys-utils/nsenter /usr/bin/nsenter

COPY --from=nftables /runtime/lib /lib
COPY --from=nftables /runtime/usr/bin/nft /usr/bin/nft
COPY --from=nftables /runtime/usr/lib /usr/lib
COPY --from=nftables /runtime/usr/share/nftables /usr/share/nftables

USER 0:0
ENV PATH="/usr/local/bin:/usr/bin:/usr/libexec/crio:/opt/cni/bin"
ENTRYPOINT ["/usr/local/bin/worker-init"]

ENTRYPOINT ["ramjet", "[", "my-prog", "args", "]", ""]