#pragma once

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void start_process(const char *root, const char *working_dir, const char *command, char **env) {
    char *argv[] = {
        (char *)command,
        NULL
    };

    if (root != NULL) chroot(root);
    if (working_dir != NULL) chdir(working_dir);

    const int status = execve(command, argv, env);
    fprintf(stderr, "init: failed to start '%s': %s\n", command, strerror(errno));
    _exit(status);
}

typedef int (*child_fn)(void);

static pid_t fork_child(const child_fn fn){
    const pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) _exit(fn());
    return pid;
}