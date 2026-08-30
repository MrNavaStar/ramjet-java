#define GNU_SOURCE

#include "../runtime/include/runtime.h"
#include "../common/include/exec.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <sys/mount.h>

static volatile sig_atomic_t shutting_down = 0;

static void signal_handler(const int sig) {
    (void)sig;
    shutting_down = 1;
}

int main(void) {
    struct sigaction sa = {
        .sa_handler = signal_handler,
        .sa_flags = 0
    };
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);
    // Ignore SIGPIPE so that a child cannot accidentally terminate the init process through a broken pipe.
    signal(SIGPIPE, SIG_IGN);

    // Start Child processes
    #define CHILDREN 1
    pid_t pids[CHILDREN];
    pids[0] = fork_child(start_image);

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

    // Shutdown: terminate configured children.
    for (size_t i = 0; i < CHILDREN; i++)
        if (pids[i] > 0) kill(pids[i], SIGTERM);

    // Reap everything remaining.
    while (waitpid(-1, NULL, 0) > 0) {}
    return 0;
}