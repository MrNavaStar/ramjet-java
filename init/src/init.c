#define _GNU_SOURCE

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <fcntl.h>
#include <signal.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <wordexp.h>

#define MAX_ARGS      128
#define MAX_EXEC_ARGS 256

struct arg {
    char *key;
    char *value;
};

static struct arg args[MAX_ARGS];
static int nargs = 0;

static void mount_fs(const char *src, const char *target, unsigned long flags, const char *opts) {
    mkdir(target, 0755);
    if (mount(src, target, src, flags, opts) < 0 && errno != EBUSY) perror(target);
}

static void mount_virtual_fs(void) {
    mount_fs("proc", "/proc", 0, NULL);
    mount_fs("sysfs", "/sys", 0, NULL);
    mount_fs("devtmpfs", "/dev", MS_NOSUID, "mode=0755");
    mount_fs("devpts", "/dev/pts", MS_NOSUID | MS_NOEXEC, "gid=5,mode=620");
    mount_fs("tmpfs", "/dev/shm", MS_NOSUID | MS_NODEV, "mode=1777");
    mount_fs("tmpfs", "/run", MS_NOSUID | MS_NODEV, "mode=0755");
    mount_fs("tmpfs", "/tmp", MS_NOSUID | MS_NODEV, "mode=1777");
    mount_fs("cgroup2", "/sys/fs/cgroup", 0, NULL);
}

static void parse_cmdline(void) {
    FILE *f = fopen("/proc/cmdline", "r");
    if (!f) {
        perror("/proc/cmdline");
        exit(1);
    }

    char *line = NULL;
    size_t len = 0;

    if (getline(&line, &len, f) < 0) {
        perror("getline");
        exit(1);
    }

    fclose(f);

    char *tok = strtok(line, " \n");
    while (tok && nargs < MAX_ARGS) {
        char *eq = strchr(tok, '=');

        if (eq) {
            *eq = '\0';
            args[nargs].key = strdup(tok);
            args[nargs].value = strdup(eq + 1);
        } else {
            args[nargs].key = strdup(tok);
            args[nargs].value = strdup("");
        }

        nargs++;
        tok = strtok(NULL, " \n");
    }

    free(line);
}

static const char *get_arg(const char *name) {
    for (int i = 0; i < nargs; i++)
        if (!strcmp(args[i].key, name)) return args[i].value;
    return NULL;
}

static char **build_manager_argv(void) {
    char **argv = calloc(nargs + 2, sizeof(char *));
    argv[0] = "/bin/sys-manager";

    for (int i = 0; i < nargs; i++) {
        if (args[i].value[0]) asprintf(&argv[i + 1], "%s=%s", args[i].key, args[i].value);
        else argv[i + 1] = strdup(args[i].key);
    }

    argv[nargs + 1] = NULL;
    return argv;
}

static void start_manager(void){
    pid_t pid = fork();
    if (pid == 0) {
        execv("/bin/sys-manager", build_manager_argv());
        perror("execv(/bin/sys-manager)");
        _exit(127);
    }
}

static pid_t start_workload(void) {
    const char *cwd   = get_arg("workingdir");
    const char *entry = get_arg("entrypoint");
    const char *cmd   = get_arg("cmd");

    if (!entry)
        return -1;

    pid_t pid = fork();

    if (pid == 0) {

        if (cwd && chdir(cwd) < 0)
            perror("chdir");

        wordexp_t ep = {0};
        wordexp_t cp = {0};

        if (wordexp(entry, &ep, WRDE_NOCMD) != 0) {
            fprintf(stderr, "failed to parse entrypoint\n");
            _exit(127);
        }

        if (cmd &&
            wordexp(cmd, &cp, WRDE_NOCMD) != 0)
        {
            fprintf(stderr, "failed to parse cmd\n");
            wordfree(&ep);
            _exit(127);
        }

        char *argv[MAX_EXEC_ARGS];
        int argc = 0;

        for (size_t i = 0;
             i < ep.we_wordc && argc < MAX_EXEC_ARGS - 1;
             i++)
        {
            argv[argc++] = ep.we_wordv[i];
        }

        for (size_t i = 0;
             i < cp.we_wordc && argc < MAX_EXEC_ARGS - 1;
             i++)
        {
            argv[argc++] = cp.we_wordv[i];
        }

        argv[argc] = NULL;

        execvp(argv[0], argv);

        perror("execvp");
        wordfree(&ep);
        wordfree(&cp);
        _exit(127);
    }

    return pid;
}

int main(void) {
    mount_virtual_fs();
    parse_cmdline();
    start_manager();

    pid_t workload = start_workload();

    for (;;) {
        int status;
        pid_t pid = waitpid(-1, &status, 0);

        if (pid < 0) {
            if (errno == EINTR) continue;
            sleep(1);
            continue;
        }

        if (pid == workload) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return 1;
        }
    }
}