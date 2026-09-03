#include "include/layers.h"

#include <archive.h>
#include <archive_entry.h>

#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>

static void rm_rf(const char *path);

static void clear_directory(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return;

    struct dirent *e;
    char path[PATH_MAX];

    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;

        snprintf(path, sizeof(path), "%s/%s", dir, e->d_name);
        rm_rf(path);
    }

    closedir(d);
}

static void rm_rf(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return;

    if (S_ISDIR(st.st_mode)) {
        clear_directory(path);
        rmdir(path);
    }
    else unlink(path);
}

static int handle_whiteout(const char *dest, const char *name) {
    const char *base = strrchr(name, '/');
    base = base ? base + 1 : name;

    /* Not a whiteout */
    if (strncmp(base, ".wh.", 4) != 0) return 0;

    char dir[PATH_MAX];
    char target[PATH_MAX];

    const char *slash = strrchr(name, '/');
    if (slash) snprintf(dir, sizeof(dir), "%s/%.*s", dest, (int) (slash - name), name);
    else snprintf(dir, sizeof(dir), "%s", dest);

    /* Opaque directory */
    if (!strcmp(base, ".wh..wh..opq")) {
        clear_directory(dir);
        return 1;
    }

    /* Normal whiteout */
    snprintf(target, sizeof(target), "%s/%.*s%s", dir, (int)(base - name), name, base + 4);
    rm_rf(target);
    return 1;
}

static int copy_data(struct archive *in, struct archive *out) {
    const void *buffer;
    size_t size;
    la_int64_t offset;

    while (1) {
        int r = archive_read_data_block(in, &buffer, &size, &offset);
        if (r == ARCHIVE_EOF) return ARCHIVE_OK;
        if (r != ARCHIVE_OK) return r;

        r = archive_write_data_block(out, buffer, size, offset);
        if (r != ARCHIVE_OK) return r;
    }
}

static void close_archive(struct archive *in, struct archive *out) {
    archive_read_close(in);
    archive_read_free(in);
    archive_write_close(out);
    archive_write_free(out);
}

static int extract_layer_fd(const int fd, const char *root) {
    struct archive *in = archive_read_new();
    struct archive *out = archive_write_disk_new();
    if (!in || !out) return -1;

    archive_read_support_filter_gzip(in);
    archive_read_support_filter_zstd(in);
    archive_read_support_format_tar(in);

    archive_write_disk_set_options(
        out,
        ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_PERM |
        ARCHIVE_EXTRACT_ACL |
        ARCHIVE_EXTRACT_FFLAGS
    );

    if (archive_read_open_fd(in, fd, 64 * 1024) != ARCHIVE_OK) {
        fprintf(stderr, "open: %s\n", archive_error_string(in));
        archive_read_free(in);
        archive_write_free(out);
        return -1;
    }

    struct archive_entry *entry;
    while (1) {
        const int r = archive_read_next_header(in, &entry);
        if (r == ARCHIVE_EOF) break;
        if (r != ARCHIVE_OK) {
            fprintf(stderr, "header: %s\n", archive_error_string(in));
            goto error;
        }

        const char *name = archive_entry_pathname(entry);

        /* Apply whiteouts instead of extracting them */
        if (handle_whiteout(root, name)) continue;

        /*
         * Prefix every entry with the destination directory.
         */
        char path[4096];
        if (snprintf(path, sizeof(path), "%s/%s", root, name) >= (int)sizeof(path)) {
            fprintf(stderr, "path too long: %s\n", name);
            goto error;
        }

        archive_entry_set_pathname(entry, path);

        const char *hardlink = archive_entry_hardlink_utf8(entry);
        char hardlink_path[PATH_MAX];
        if (hardlink != NULL) {
            if (snprintf(hardlink_path, sizeof(hardlink_path), "%s/%s", root, hardlink) >= (int)sizeof(hardlink_path)) {
                fprintf(stderr, "hard-link path too long: %s\n", hardlink);
                goto error;
            }
            archive_entry_set_hardlink_utf8(entry, hardlink_path);
        }

        if (archive_write_header(out, entry) != ARCHIVE_OK) {
            fprintf(stderr, "header write: %s\n", archive_error_string(out));
            goto error;
        }

        if (archive_entry_size(entry) > 0 && copy_data(in, out) != ARCHIVE_OK) {
            fprintf(stderr, "data: %s\n", archive_error_string(out));
            goto error;
        }

        if (archive_write_finish_entry(out) != ARCHIVE_OK) {
            fprintf(stderr, "finish: %s\n", archive_error_string(out));
            goto error;
        }
    }

    close_archive(in, out);
    return 0;
error:
    close_archive(in, out);
    return -1;
}

int extract_embedded_layers(const char *root) {
    mkdir(root, 0755);

    DIR *dir = opendir(EMBEDDED_LAYERS_DIR);
    if (!dir) {
        perror(EMBEDDED_LAYERS_DIR);
        return -1;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        if (entry->d_type != DT_REG && entry->d_type != DT_UNKNOWN) continue;

        char filepath[PATH_MAX];
        const int len = snprintf(filepath, sizeof(filepath), "%s/%s", EMBEDDED_LAYERS_DIR, entry->d_name);
        if (len < 0 || (size_t)len >= sizeof(filepath)) {
            fprintf(stderr, "Path too long: %s/%s\n", EMBEDDED_LAYERS_DIR, entry->d_name);
            continue;
        }

        const int fd = open(filepath, O_RDONLY);
        if (fd < 0) {
            perror(filepath);
            continue;
        }

        const int result = extract_layer_fd(fd, root);
        close(fd);

        if (result != 0) {
            closedir(dir);
            return -1;
        }

        if (unlink(filepath) < 0) perror(filepath);
    }

    closedir(dir);
    return 0;
}