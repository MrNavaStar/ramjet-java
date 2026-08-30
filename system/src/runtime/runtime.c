#include "include/runtime.h"
#include "include/layers.h"
#include "../common/include/exec.h"
#include "../common/include/dotenv.h"

#include <stdio.h>
#include <unistd.h>
#include <sys/mount.h>
#include <sys/stat.h>

static void mount_fs(const char *root, const char *target, const char *type, const char *options) {
    char filepath[16];
    snprintf(filepath, sizeof(filepath), "%s/%s", root, target);
    mkdir(filepath, 0755);

    if (mount(type, filepath, type, 0, options) < 0) {
        if (errno != EBUSY) exit(1);
    }
}

int start_image() {
    if (env_load("/layers", true) != 0) {
        fprintf(stderr, "runtime: failed to load image settings");
        return -1;
    }

    const char *cmd = getenv("RAMJET_IMAGE_CMD");
    const char *env = getenv("RAMJET_IMAGE_ENV");

    if (cmd == NULL) {
        fprintf(stderr, "runtime: failed to find RAMJET_IMAGE_CMD environment variable");
        return -1;
    }
    if (env == NULL) {
        fprintf(stderr, "runtime: failed to find RAMJET_IMAGE_ENV environment variable");
        return -1;
    }

    if (access(EMBEDDED_LAYERS_DIR, F_OK) == 0) {
        extract_embedded_layers(ROOT_DIR);
    } else {
        //TODO: ask mgmt service for layers
        fprintf(stderr, "runtime: failed to find embedded layers");
        return -1;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        perror("runtime: fork");
        return -1;
    }

    if (pid == 0) {
        /*
         * Mount essential filesystems.
         */
        mount_fs(ROOT_DIR, "proc", "proc", NULL);
        mount_fs(ROOT_DIR, "sys", "sysfs", NULL);
        mount_fs(ROOT_DIR, "dev", "devtmpfs", "mode=0755");
        mount_fs(ROOT_DIR, "run", "tmpfs", "mode=0755");
        mount_fs(ROOT_DIR, "tmp", "tmpfs", "mode=1777");

        /*mkdir("/dev/pts", 0755);
        mount_fs(ROOT_DIR, "devpts", "dev/pts", "devpts", "mode=0620");

        mkdir("/dev/shm", 01777);
        mount_fs(ROOT_DIR, "tmpfs", "dev/shm", "tmpfs", "mode=1777");*/

        start_process(ROOT_DIR, cmd, NULL);
    }

    return 0;
}