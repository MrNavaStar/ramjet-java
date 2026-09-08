return {
    image = "oci://docker.io/library/nginx:trixie-perl",
    --image = "oci://docker.io/grafana/grafana:nightly";
    kernel = "file:///vmlinuz"

    mounts = {
        media_01 = "/media:/mnt/media_01"
    }
}