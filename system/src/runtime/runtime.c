#include "include/runtime.h"
#include "include/layers.h"
#include "include/config.h"
#include "../common/include/exec.h"
#include "../common/include/files.h"

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
    size_t config_size;
    char *raw_config;
    if (access(EMBEDDED_CONFIG_DIR, F_OK) == 0) {
        raw_config = read_file(EMBEDDED_CONFIG_DIR, &config_size);
        if (!raw_config) {
            fprintf(stderr, "runtime: failed to read OCI config\n");
            return 1;
        }
    } else {
        //TODO: ask mgmt service for config
        fprintf(stderr, "runtime: failed to find embedded config\n");
        return 1;
    }

    if (access(EMBEDDED_LAYERS_DIR, F_OK) == 0) {
        if (layers_extract_dir_to(EMBEDDED_LAYERS_DIR, ROOT_DIR) != 0) {
            fprintf(stderr, "runtime: failed to extract OCI layers\n");
            return 1;
        }
    } else {
        //TODO: ask mgmt service for layers
        fprintf(stderr, "runtime: failed to find embedded layers\n");
        return 1;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        perror("runtime: fork");
        return 1;
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

        oci_config_t config;
        if (oci_config_parse(raw_config, config_size, &config) != 0) {
            fprintf(stderr, "runtime: failed to parse OCI config\n");
            return 1;
        }

        start_process(ROOT_DIR, config.working_dir, *oci_get_exec_cmd(config), config.env);
    }
    return 0;
}