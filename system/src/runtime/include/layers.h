#pragma once

#define EMBEDDED_LAYERS_DIR "/embedded/layers/"
#define EMBEDDED_CONFIG_DIR "/embedded/config.json"
#define ROOT_DIR "/root/"

/*
 * Reads a single OCI image layer (a tar stream, optionally compressed
 * with gzip/zstd/xz/bzip2, or uncompressed) from the given open file
 * descriptor and extracts it into dest_root.
 *
 * Returns 0 on success, -1 on failure. Handles OCI whiteout files
 * ("<dir>/.wh.<name>") and opaque-directory whiteouts
 * ("<dir>/.wh..wh..opq") per the OCI image spec.
 *
 * Intended to be called once per layer, in bottom-to-top order, to
 * assemble a full rootfs under dest_root before chrooting into it -- see
 * the design notes at the top of layers.c for how symlinks, hardlinks
 * and whiteouts are handled with that chroot in mind.
 *
 * Does not close fd.
 */

int layers_extract_to(int fd, const char *dest_root);

/*
 * Extracts every OCI layer found in layers_dir into dest_root, in
 * ascending order of layer number, deleting each layer file once it
 * has been successfully extracted. Filenames are expected to look
 * like "<layernumber-zero-padded>-<digest>.tar"
 * entries that don't match are skipped with a warning rather than
 * treated as an error.
 *
 * If a layer fails to extract, processing stops immediately, that
 * layer and all later ones are left on disk, and -1 is returned.
 * Layers already applied (and deleted) before the failure stay
 * applied.
 */
int layers_extract_dir_to(const char *layers_dir, const char *dest_root);