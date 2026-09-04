#pragma once

#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <string.h>
#include <errno.h>

#define PATH_MAX 4096

static char *read_file(const char *path, size_t *size_out) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;

    fseek(file, 0, SEEK_END);
    const long size = ftell(file);
    rewind(file);

    if (size < 0) {
        fclose(file);
        return NULL;
    }

    char *buffer = malloc((size_t)size + 1);
    if (!buffer) {
        fclose(file);
        return NULL;
    }

    const size_t read = fread(buffer, 1, (size_t)size, file);
    fclose(file);

    if (read != (size_t)size) {
        free(buffer);
        return NULL;
    }

    buffer[size] = '\0';
    if (size_out) *size_out = (size_t)size;
    return buffer;
}

/* mkdir -p */
static int mkdir_p(const char *path) {
    char tmp[PATH_MAX];
    const size_t len = strlen(path);
    if (len == 0 || len >= sizeof(tmp)) {
        errno = EINVAL;
        return -1;
    }
    memcpy(tmp, path, len + 1);

    for (char *p = tmp + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

/* Final path component of "path" (portion after the last '/', or the
 * whole string if there's no '/'). */
static const char *path_basename(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

/* Directory portion of "path" (everything before the last '/'),
 * copied into out. If there's no '/', out is set to ".". Always
 * NUL-terminated (truncates rather than overflowing). */
static void path_dirname(const char *path, char *out, const size_t out_size) {
    const char *slash = strrchr(path, '/');
    if (!slash) {
        snprintf(out, out_size, ".");
        return;
    }
    size_t len = (size_t)(slash - path);
    if (len >= out_size) len = out_size - 1;
    memcpy(out, path, len);
    out[len] = '\0';
}