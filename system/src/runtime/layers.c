/*
 * ---------------------------------------------------------------------
 * Design notes: symlinks, hardlinks, and the later chroot into /root
 * ---------------------------------------------------------------------
 *
 * Rather than manually prepending "/root/" to every path string that
 * comes out of the archive (easy to get subtly wrong for hardlinks,
 * symlink targets, etc.), this code chdir()s into the destination
 * root exactly once and then extracts every entry using its ORIGINAL,
 * archive-relative path. libarchive's archive_write_disk operates
 * relative to the process's current directory, so this keeps every
 * created file, directory, and link scoped under the destination
 * without any manual string surgery. This is the same pattern bsdtar
 * itself uses.
 *
 * With that in place:
 *
 *   - Symlinks are left completely untouched -- archive_entry_symlink()
 *     is never rewritten. A layer entry like "lib -> usr/lib" or an
 *     absolute target like "/lib64/ld-linux-x86-64.so.2" is meant to
 *     resolve correctly *after* you chroot into /root, so rewriting
 *     the stored target text would break exactly the images we care
 *     about (this is extremely common in modern usrmerge-style root
 *     filesystems). The symlink is just a text blob on disk; it is
 *     not resolved at creation time.
 *
 *   - Hardlinks (archive_entry_hardlink()) reference another file
 *     that was already extracted earlier in the same archive, given
 *     as an archive-relative path. Since we never prefix paths, that
 *     reference is already correct relative to our chdir()'d cwd and
 *     needs no rewriting either -- we only re-run it through the same
 *     normalization/safety checks as every other path.
 *
 *   - We enable archive_write_disk's ARCHIVE_EXTRACT_SECURE_SYMLINKS,
 *     ARCHIVE_EXTRACT_SECURE_NODOTDOT and
 *     ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS. These reject entries
 *     that try to use ".." or an absolute path to climb out of the
 *     destination tree, and refuse to extract *through* a symlink
 *     that appears to point outside the tree currently being
 *     extracted.
 *
 *   - Whiteout handling (see below) happens in this program's own
 *     code, *before* archive_write_header() is ever called for those
 *     entries -- so libarchive's SECURE_* checks never see them. We
 *     therefore hand-validate every path (and hardlink target)
 *     ourselves via path_is_safe() before touching the filesystem at
 *     all, rather than relying solely on libarchive.
 *
 * Known limitation, worth being upfront about: ARCHIVE_EXTRACT_SECURE_
 * SYMLINKS is deliberately conservative. Because it can't know that
 * "/root" is standing in for a future "/", it may refuse to extract
 * *through* a legitimate absolute symlink (e.g. a directory symlink
 * "/lib -> /usr/lib" left by an earlier entry) even though that
 * symlink is completely correct once you've chrooted. If you see
 * archive_write_header() warnings on such paths, that's this check
 * firing. libarchive doesn't offer a "resolve symlinks as if this
 * were the real root" mode -- doing that properly means walking the
 * path yourself and clamping each symlink target to stay under
 * dest_root (similar in spirit to openat2's RESOLVE_IN_ROOT, or
 * projects like cyphar/filepath-securejoin), which is real added
 * complexity beyond what a libarchive-based extractor gives you for
 * free. That's out of scope here, but worth knowing if you hit it.
 *
 * ---------------------------------------------------------------------
 * Whiteout handling
 * ---------------------------------------------------------------------
 *
 * Per the OCI image spec:
 *
 *   - A regular whiteout, a file named ".wh.<name>" inside directory
 *     <dir>, means "<name>" must not exist in the result: any file or
 *     directory already extracted (by an earlier layer) at
 *     <dir>/<name> is removed, recursively. The whiteout marker file
 *     itself is never written to disk.
 *
 *   - An opaque whiteout, a file named ".wh..wh..opq" inside
 *     directory <dir>, means all of <dir>'s existing contents (from
 *     earlier layers) are removed, but <dir> itself is kept so this
 *     layer's own entries can still be written into it. The marker
 *     file itself is never written to disk either.
 *
 * This function only extracts ONE layer. To build a full rootfs, call
 * it once per layer in bottom-to-top order; whiteouts in a later
 * layer will correctly delete content already materialized on disk by
 * an earlier call.
 */

#define _GNU_SOURCE

#include "include/layers.h"
#include "../common/include/files.h"

#include <archive.h>
#include <archive_entry.h>
#include <ctype.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define WHITEOUT_PREFIX     ".wh."
#define WHITEOUT_PREFIX_LEN (sizeof(WHITEOUT_PREFIX) - 1)
#define WHITEOUT_OPAQUE     ".wh..wh..opq"

#define READ_BLOCK_SIZE (64 * 1024)

/* Strip a leading "./" (possibly repeated) and any leading '/'
 * characters, so entries like "./etc/passwd" or (a defensive case)
 * "/etc/passwd" become plain relative paths. Returns a pointer into
 * the original string; does not allocate. */
static const char *normalize_entry_path(const char *path) {
    for (;;) {
        if (path[0] == '.' && path[1] == '/') {
            path += 2;
            continue;
        }
        if (path[0] == '/') {
            path += 1;
            continue;
        }
        break;
    }
    return path;
}

/* Reject anything that could climb out of the extraction root: a
 * (still) absolute path, or any ".." path component. An empty string
 * or "." is considered safe (it refers to the root itself). */
static int path_is_safe(const char *path) {
    if (path[0] == '\0' || strcmp(path, ".") == 0) return 0;
    if (path[0] == '/') return -1;

    const char *p = path;
    while (*p != '\0') {
        const char *seg_start = p;
        while (*p != '\0' && *p != '/') p++;
        const size_t seg_len = (size_t)(p - seg_start);
        if (seg_len == 2 && seg_start[0] == '.' && seg_start[1] == '.') return -1;
        if (*p == '/') p++;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* Filesystem helpers for whiteout processing. All paths passed to    */
/* these are relative -- they operate against the process's current  */
/* directory, which oci_layer_extract_to() has already chdir()'d to  */
/* the extraction root for the duration of the extraction.            */
/* ------------------------------------------------------------------ */

static int nftw_remove_cb(const char *fpath, const struct stat *sb, int type_flag, struct FTW *ftwbuf) {
    (void)sb;
    (void)ftwbuf;
    const int rv = type_flag == FTW_DP ? rmdir(fpath) : unlink(fpath);
    if (rv != 0 && errno != ENOENT) fprintf(stderr, "layers: warning: failed to remove %s: %s\n", fpath, strerror(errno));
    return 0; /* keep walking even if one removal failed */
}

/* Recursively remove whatever is at (relative) path -- file, symlink,
 * or directory tree. Missing path is not an error. FTW_PHYS ensures
 * we never follow a symlink while walking (we'd unlink the symlink
 * itself, not descend into whatever it points at). */
static void remove_path_recursive(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return;

    if (S_ISDIR(st.st_mode))
        nftw(path, nftw_remove_cb, 32, FTW_PHYS | FTW_DEPTH);
    else if (unlink(path) != 0 && errno != ENOENT)
        fprintf(stderr, "layers: warning: failed to remove %s: %s\n", path, strerror(errno));
}

/* Opaque whiteout: remove everything currently inside (relative)
 * dir, but leave dir itself in place. Missing dir is not an error. */
static void clear_directory_contents(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) {
        if (errno != ENOENT) fprintf(stderr, "layers: warning: opendir %s: %s\n", dir, strerror(errno));
        return;
    }

    struct dirent *de;
    char child[PATH_MAX];
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;

        const int n = snprintf(child, sizeof(child), "%s/%s", dir, de->d_name);
        if (n < 0 || (size_t)n >= sizeof(child)) continue;

        remove_path_recursive(child);
    }
    closedir(d);
}

/* ------------------------------------------------------------------ */
/* Archive copying                                                    */
/* ------------------------------------------------------------------ */

static int copy_data(struct archive *ar, struct archive *aw) {
    const void *buf;
    size_t size;
    int64_t offset;

    for (;;) {
        const int r = archive_read_data_block(ar, &buf, &size, &offset);
        if (r == ARCHIVE_EOF) return ARCHIVE_OK;
        if (r < ARCHIVE_OK) return r;

        const int wr = archive_write_data_block(aw, buf, size, offset);
        if (wr < ARCHIVE_OK) return wr;
    }
}

/* Handle one archive entry: either a whiteout marker (which mutates
 * the filesystem directly and is never written) or a regular entry
 * (extracted relative to the current directory). Returns 0 to
 * continue, -1 on a fatal error that should abort the whole layer. */
static int process_entry(struct archive *a, struct archive *aw, struct archive_entry *entry) {
    const char *raw_path = archive_entry_pathname(entry);
    if (raw_path == NULL || raw_path[0] == '\0') {
        archive_read_data_skip(a);
        return 0;
    }

    const char *rel_path = normalize_entry_path(raw_path);
    if (rel_path[0] == '\0' || strcmp(rel_path, ".") == 0) {
        /* Entry for the layer root itself -- destination already
         * exists, nothing to do. */
        archive_read_data_skip(a);
        return 0;
    }

    if (path_is_safe(rel_path) != 0) {
        fprintf(stderr, "layers: skipping entry with unsafe path: %s\n", raw_path);
        archive_read_data_skip(a);
        return 0;
    }

    const char *base = path_basename(rel_path);
    char dir[PATH_MAX];
    path_dirname(rel_path, dir, sizeof(dir));

    if (strcmp(base, WHITEOUT_OPAQUE) == 0) {
        clear_directory_contents(dir);
        archive_read_data_skip(a);
        return 0;
    }

    if (strncmp(base, WHITEOUT_PREFIX, WHITEOUT_PREFIX_LEN) == 0) {
        const char *victim_name = base + WHITEOUT_PREFIX_LEN;
        char victim_path[PATH_MAX];
        const int n = snprintf(victim_path, sizeof(victim_path), "%s/%s", dir, victim_name);
        if (n > 0 && (size_t)n < sizeof(victim_path)) remove_path_recursive(victim_path);
        archive_read_data_skip(a);
        return 0;
    }

    /* Regular entry. Extract using the archive-relative path; the
     * caller has already chdir()'d to the extraction root, so this
     * lands in the right place without any manual "/root/" prefix. */
    char path_buf[PATH_MAX];
    if ((size_t)snprintf(path_buf, sizeof(path_buf), "%s", rel_path) >= sizeof(path_buf)) {
        fprintf(stderr, "layers: skipping entry with too-long path: %s\n", raw_path);
        archive_read_data_skip(a);
        return 0;
    }
    archive_entry_set_pathname(entry, path_buf);

    const char *hardlink = archive_entry_hardlink(entry);
    if (hardlink != NULL && hardlink[0] != '\0') {
        const char *rel_hardlink = normalize_entry_path(hardlink);
        if (path_is_safe(rel_hardlink) != 0) {
            fprintf(stderr, "layers: skipping entry with unsafe hardlink target: %s\n", hardlink);
            archive_read_data_skip(a);
            return 0;
        }
        char hl_buf[PATH_MAX];
        if ((size_t)snprintf(hl_buf, sizeof(hl_buf), "%s", rel_hardlink) >=
            sizeof(hl_buf)) {
            fprintf(stderr, "layers: skipping entry with too-long hardlink target: %s\n", hardlink);
            archive_read_data_skip(a);
            return 0;
        }
        archive_entry_set_hardlink(entry, hl_buf);
    }

    /* Symlink targets (archive_entry_symlink()) are intentionally
     * left untouched -- see the design notes at the top of this file. */

    const int r = archive_write_header(aw, entry);
    if (r < ARCHIVE_OK) fprintf(stderr, "layers: %s\n", archive_error_string(aw));
    if (r == ARCHIVE_FATAL) return -1;

    if (r >= ARCHIVE_WARN && copy_data(a, aw) < ARCHIVE_OK) fprintf(stderr, "layers: %s\n", archive_error_string(aw));

    const int fr = archive_write_finish_entry(aw);
    if (fr < ARCHIVE_OK) fprintf(stderr, "layers: %s\n", archive_error_string(aw));
    if (fr == ARCHIVE_FATAL) return -1;
    return 0;
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

int layers_extract_to(int fd, const char *dest_root) {
    if (fd < 0 || dest_root == NULL || dest_root[0] != '/') {
        errno = EINVAL;
        return -1;
    }

    if (mkdir_p(dest_root) != 0) return -1;

    const int saved_cwd = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (saved_cwd < 0) return -1;

    if (chdir(dest_root) != 0) {
        close(saved_cwd);
        return -1;
    }

    int ret = 0;
    struct archive *a = archive_read_new();
    struct archive *aw = archive_write_disk_new();
    if (a == NULL || aw == NULL) {
        if (a) archive_read_free(a);
        if (aw) archive_write_free(aw);
        fchdir(saved_cwd);
        close(saved_cwd);
        errno = ENOMEM;
        return -1;
    }

    archive_read_support_format_tar(a);
    archive_read_support_filter_all(a);

    archive_write_disk_set_options(aw,
        ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_PERM |
        ARCHIVE_EXTRACT_ACL |
        ARCHIVE_EXTRACT_FFLAGS |
        ARCHIVE_EXTRACT_XATTR |
        ARCHIVE_EXTRACT_OWNER |
        ARCHIVE_EXTRACT_UNLINK |
        ARCHIVE_EXTRACT_SECURE_SYMLINKS |
        ARCHIVE_EXTRACT_SECURE_NODOTDOT |
        ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS);
    archive_write_disk_set_standard_lookup(aw);

    if (archive_read_open_fd(a, fd, READ_BLOCK_SIZE) != ARCHIVE_OK) {
        fprintf(stderr, "layers: archive_read_open_fd: %s\n", archive_error_string(a));
        ret = -1;
        goto cleanup;
    }

    for (;;) {
        struct archive_entry *entry;
        const int r = archive_read_next_header(a, &entry);
        if (r == ARCHIVE_EOF) break;
        if (r == ARCHIVE_RETRY) continue;
        if (r < ARCHIVE_WARN) {
            fprintf(stderr, "layers: archive_read_next_header: %s\n", archive_error_string(a));
            if (r == ARCHIVE_FATAL) {
                ret = -1;
                break;
            }
            continue;
        }
        if (r == ARCHIVE_WARN) fprintf(stderr, "layers: warning: %s\n", archive_error_string(a));

        if (process_entry(a, aw, entry) != 0) {
            ret = -1;
            break;
        }
    }

cleanup:
    archive_read_close(a);
    archive_read_free(a);
    archive_write_close(aw);
    archive_write_free(aw);

    if (fchdir(saved_cwd) != 0) fprintf(stderr, "layers: warning: failed to restore cwd: %s\n", strerror(errno));
    close(saved_cwd);

    return ret;
}

/*
 * Expects filenames of the form "<layernumber-zero-padded>-<digest>.tar"
 * optionally followed by a compression suffix, e.g.:
 *   0000-sha256:abcd1234....tar.gz
 *   0001-sha256:ef567890....tar.zst
 *   0002-sha256:12345678....tar
 *
 * We don't need to inspect the compression suffix ourselves --
 * archive_read_support_filter_all() (enabled inside
 * oci_layer_extract_to()) auto-detects it from the stream itself, not
 * the filename. All this parsing needs to do is recover the leading
 * layer number for ordering, and sanity-check that the name looks
 * like one of our layer files at all so we don't choke on stray files
 * (a lockfile, a README, an in-progress ".tmp" download, etc.) that
 * might happen to live in the same directory.
 */
struct layer_file {
    char *path;
    unsigned long layer_num;
};

static int layer_file_cmp(const void *a, const void *b) {
    const struct layer_file *la = a;
    const struct layer_file *lb = b;
    if (la->layer_num != lb->layer_num)
        return la->layer_num < lb->layer_num ? -1 : 1;
    return strcmp(la->path, lb->path);
}

/* Parse the leading "<digits>-" layer-number prefix and require a
 * ".tar" somewhere after it. Returns 0 and fills *out on success, -1
 * if name doesn't look like one of our layer files. */
static int parse_layer_number(const char *name, unsigned long *out) {
    if (!isdigit((unsigned char)name[0])) return -1;

    char *end;
    errno = 0;
    const unsigned long n = strtoul(name, &end, 10);
    if (errno != 0 || end == name || *end != '-') return -1;
    if (strstr(end, ".tar") == NULL) return -1;

    *out = n;
    return 0;
}

/*
 * Extracts every OCI layer found in layers_dir into dest_root, in
 * ascending order of the leading "<layernumber>-" prefix in each
 * filename (see parse_layer_number() above), deleting each layer file
 * once it has been successfully extracted.
 *
 * Entries that don't match the expected naming pattern, or that
 * aren't regular files, are skipped with a warning rather than
 * treated as an error -- so it's safe to point this at a directory
 * that also has other bookkeeping files in it.
 *
 * If a layer fails to extract, processing stops immediately: that
 * layer and every layer after it are left on disk untouched (so you
 * can inspect what went wrong), and -1 is returned. Layers before the
 * failure have already been both applied and deleted, since each
 * layer is deleted right after it succeeds rather than in a batch at
 * the end.
 */
int layers_extract_dir_to(const char *layers_dir, const char *dest_root) {
    if (layers_dir == NULL || dest_root == NULL) {
        errno = EINVAL;
        return -1;
    }

    DIR *d = opendir(layers_dir);
    if (!d) return -1;

    struct layer_file *files = NULL;
    size_t count = 0, cap = 0;
    int ret = 0;

    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;

        unsigned long layer_num;
        if (parse_layer_number(de->d_name, &layer_num) != 0) {
            fprintf(stderr, "layers: skipping entry that doesn't look like a layer file (<num>-<digest>.tar): %s/%s\n", layers_dir, de->d_name);
            continue;
        }

        char full_path[PATH_MAX];
        const int n = snprintf(full_path, sizeof(full_path), "%s/%s", layers_dir, de->d_name);
        if (n < 0 || (size_t)n >= sizeof(full_path)) {
            fprintf(stderr, "layers: skipping entry with too-long path: %s/%s\n", layers_dir, de->d_name);
            continue;
        }

        struct stat st;
        if (stat(full_path, &st) != 0 || !S_ISREG(st.st_mode)) {
            fprintf(stderr, "layers: skipping non-regular-file entry: %s\n", full_path);
            continue;
        }

        if (count == cap) {
            const size_t new_cap = cap == 0 ? 16 : cap * 2;
            struct layer_file *tmp = realloc(files, new_cap * sizeof(*files));
            if (!tmp) {
                ret = -1;
                break;
            }
            files = tmp;
            cap = new_cap;
        }

        char *path_copy = strdup(full_path);
        if (!path_copy) {
            ret = -1;
            break;
        }
        files[count].path = path_copy;
        files[count].layer_num = layer_num;
        count++;
    }
    closedir(d);

    if (ret == 0) {
        qsort(files, count, sizeof(*files), layer_file_cmp);

        for (size_t i = 0; i < count; i++) {
            const int fd = open(files[i].path, O_RDONLY);
            if (fd < 0) {
                fprintf(stderr, "layers: open %s: %s\n", files[i].path, strerror(errno));
                ret = -1;
                break;
            }

            const int rc = layers_extract_to(fd, dest_root);
            close(fd);

            if (rc != 0) {
                fprintf(stderr, "layers: failed to extract %s, leaving it (and any later layers) in place and stopping\n", files[i].path);
                ret = -1;
                break;
            }

            if (unlink(files[i].path) != 0) {
                fprintf(stderr, "layers: warning: extracted %s but failed to delete it: %s\n", files[i].path, strerror(errno));
                /* not fatal -- the layer itself was applied successfully */
            }
        }
    }

    for (size_t i = 0; i < count; i++) free(files[i].path);
    free(files);
    return ret;
}
