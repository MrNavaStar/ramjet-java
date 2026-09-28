ARG CRIO_VERSION=1.36.5
ARG TARGETARCH

FROM alpine:edge AS crio
ARG CRIO_VERSION
ARG TARGETARCH

RUN apk add --no-cache ca-certificates curl && mkdir -p /crio
RUN curl --fail --location --retry 3 "https://storage.googleapis.com/cri-o/artifacts/cri-o.${TARGETARCH}.v${CRIO_VERSION}.tar.gz" | tar -xz -C /crio

RUN printf '%s\n' \
    '[crio.image]' \
    'signature_policy = "/etc/crio/policy.json"' \
    '[crio.runtime]' \
    'default_runtime = "crun"' \
    'runtime_path = "/usr/libexec/crio/crun"' \
    'monitor_path = "/usr/libexec/crio/conmon"' \
    '[crio.network]' \
    'network_dir = "/etc/cni/net.d"' \
    'plugin_dirs = ["/opt/cni/bin"]' \
    > /crio/cri-o/etc/crio.conf

FROM scratch

COPY --from=crio /crio/cri-o/cni-plugins/. /opt/cni/bin/
COPY --from=crio /crio/cri-o/bin/crun /usr/libexec/crio/crun
COPY --from=crio /crio/cri-o/bin/conmon /usr/libexec/crio/conmon
COPY --from=crio /crio/cri-o/etc/10-crio.conf /etc/crio/10-crio.conf
COPY --from=crio /crio/cri-o/etc/crio-umount.conf /etc/crio/crio-umount.conf
COPY --from=crio /crio/cri-o/contrib/policy.json /etc/crio/policy.json
COPY --from=crio /crio/cri-o/etc/crio.conf /etc/crio/crio.conf