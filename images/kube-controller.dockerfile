FROM packages/kubernetes.dockerfile AS kube
FROM scratch

COPY --from=kube /kube-controller-manager /usr/local/bin/kube-controller-manager

USER 65532:65532
ENTRYPOINT ["/usr/local/bin/kube-controller-manager"]