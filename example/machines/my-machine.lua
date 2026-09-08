return {
    uuid = "00000000-0000-0000-0000-000000000000",
    arch = "amd64",
    profile = "nginx",

    devices = {
        media_01 = {
            path = "/dev/disk/by-label/media_01",
            fstype = "ext4" -- optional
        },
        media_02 = "/dev/disk/by-label/media_02",
    }
}