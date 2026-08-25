#define GNU_SOURCE

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sys/mount.h>

#include "../common/include/exec.h"

static volatile sig_atomic_t shutting_down = 0;

static void signal_handler(const int sig) {
    (void)sig;
    shutting_down = 1;
}

/*
 * PID 1 should reap orphaned processes, so explicitly become
 * a subreaper-like init by simply being PID 1 in the namespace.
 */
int main(void) {
    char *inlet = getenv("init");

    if (!inlet || *inlet == '\0') {
        fprintf(stderr, "init: no 'init' environment variable specified\n");
        return 1;
    }

    struct sigaction sa = {
        .sa_handler = signal_handler,
        .sa_flags = 0
    };

    sigemptyset(&sa.sa_mask);

    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);

    /*
     * Ignore SIGPIPE so that a child cannot accidentally terminate the init process through a broken pipe.
     */
    signal(SIGPIPE, SIG_IGN);

    size_t command_count = 0;
    pid_t *pids = calloc(command_count, sizeof(pid_t));

    if (!pids) {
        perror("init: calloc");
        return 1;
    }

    /*
    * Start all requested processes.
    */
    char *command;
    while ((command = strsep(&inlet, ","))) {
        const pid_t pid = fork();
        if (pid < 0) {
            perror("init: fork");
            continue;
        }

        if (pid == 0) start_process(command, NULL, NULL);

        pids[command_count] = pid;
        command_count++;
        fprintf(stderr, "init: started %s (pid %d)\n", command, pid);
    }

    /*
     * Main PID 1 loop.
     *
     * waitpid(-1, ...) waits for ANY child, which means this also
     * reaps grandchildren that have been orphaned and adopted by PID 1.
     */
    while (!shutting_down) {
        int status;
        const pid_t pid = waitpid(-1, &status, 0);

        if (pid < 0) {
            if (errno == EINTR) continue;
            if (errno == ECHILD) break;

            perror("init: waitpid");
            continue;
        }

        if (WIFEXITED(status))  fprintf(stderr, "init: process %d exited with status %d\n", pid, WEXITSTATUS(status));
        else if (WIFSIGNALED(status)) fprintf(stderr, "init: process %d killed by signal %d\n", pid, WTERMSIG(status));
    }

    /*
     * Shutdown: terminate configured children.
     */
    for (size_t i = 0; i < command_count; i++)
        if (pids[i] > 0) kill(pids[i], SIGTERM);

    /*
     * Reap everything remaining.
     */
    while (waitpid(-1, NULL, 0) > 0) {}

    free(pids);
    return 0;
}