#pragma once

#include <stdio.h>
#include <stdlib.h>

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