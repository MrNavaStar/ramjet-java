#include "include/config.h"

#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <yyjson.h>

static int parse_string_array(yyjson_val *array, char ***out_values, size_t *out_count) {
    *out_values = NULL;
    *out_count = 0;

    /* Missing or null arrays are valid. */
    if (!array || yyjson_is_null(array)) return 0;
    if (!yyjson_is_arr(array)) return -1;

    const size_t count = yyjson_arr_size(array);
    if (count == 0) return 0;

    char **values = calloc(count, sizeof(char *));
    if (!values) return -1;

    size_t index, max;
    yyjson_val *value;
    yyjson_arr_foreach(array, index, max, value) {
        if (!yyjson_is_str(value)) goto error;
        const char *str = yyjson_get_str(value);
        values[index] = strdup(str);
        if (!values[index]) goto error;
    }

    *out_values = values;
    *out_count = count;

    return 0;

    error:
        for (size_t i = 0; i < count; i++) free(values[i]);

    free(values);
    return -1;
}

void oci_config_free(oci_config_t *config) {
    if (!config) return;

    for (size_t i = 0; i < config->cmd_count; i++) free(config->cmd[i]);
    for (size_t i = 0; i < config->entrypoint_count; i++) free(config->entrypoint[i]);
    for (size_t i = 0; i < config->env_count; i++) free(config->env[i]);

    free(config->cmd);
    free(config->entrypoint);
    free(config->env);
    free(config->working_dir);
    memset(config, 0, sizeof(*config));
}

int oci_config_parse(const char *json, const size_t json_len, oci_config_t *config) {
    memset(config, 0, sizeof(*config));

    yyjson_doc *doc = yyjson_read(json, json_len, 0);
    if (!doc) return -1;

    yyjson_val *root = yyjson_doc_get_root(doc);
    if (!yyjson_is_obj(root)) {
        yyjson_doc_free(doc);
        return -1;
    }

    yyjson_val *oci_config = yyjson_obj_get(root, "config");
    if (!oci_config || !yyjson_is_obj(oci_config)) {
        yyjson_doc_free(doc);
        return -1;
    }

    yyjson_val *cmd = yyjson_obj_get(oci_config, "Cmd");
    yyjson_val *entrypoint = yyjson_obj_get(oci_config, "Entrypoint");
    yyjson_val *env = yyjson_obj_get(oci_config, "Env");
    yyjson_val *working_dir = yyjson_obj_get(oci_config, "WorkingDir");

    if (parse_string_array(cmd, &config->cmd, &config->cmd_count) != 0) goto error;
    if (parse_string_array(entrypoint, &config->entrypoint, &config->entrypoint_count) != 0) goto error;
    if (parse_string_array(env, &config->env, &config->env_count) != 0) goto error;

    if (working_dir && !yyjson_is_null(working_dir)) {
        if (!yyjson_is_str(working_dir)) goto error;
        config->working_dir = strdup(yyjson_get_str(working_dir));
        if (!config->working_dir) goto error;
    }

    yyjson_doc_free(doc);

    return 0;

    error:
        yyjson_doc_free(doc);
    oci_config_free(config);
    return -1;
}

char **oci_get_exec_cmd(const oci_config_t config) {
    const size_t size = config.cmd_count + config.entrypoint_count;
    char **result = malloc((size + 1) * sizeof(char *));
    if (!result) return NULL;

    for (size_t i = 0; i < config.entrypoint_count; i++) result[i] = config.entrypoint[i];
    for (size_t i = 0; i < config.cmd_count; i++) result[config.entrypoint_count + i] = config.cmd[i];

    result[size] = NULL;
    return result;
}