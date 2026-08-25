#pragma once

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void start_process(const char *root, const char *command, char **env) {
    char *argv[] = {
        (char *)command,
        NULL
    };

    if (root != NULL) chroot(root);
    const int status = execve(command, argv, env);
    fprintf(stderr, "init: failed to start '%s': %s\n", command, strerror(errno));
    _exit(status);
}