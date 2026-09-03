#pragma once
#include <stddef.h>

typedef struct {
    char **cmd;
    size_t cmd_count;

    char **entrypoint;
    size_t entrypoint_count;

    char **env;
    size_t env_count;

    char *working_dir;
} oci_config_t;

int oci_config_parse(const char *json, size_t json_len, oci_config_t *config);

void oci_config_free(oci_config_t *config);

char **oci_get_exec_cmd(oci_config_t config);