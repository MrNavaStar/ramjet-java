#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="${1:-.}"
PACKAGE_NAME="$(basename "$(realpath "$PACKAGE_DIR")")"

for dockerfile in "$PACKAGE_DIR"/*.dockerfile; do
    [[ -e "$dockerfile" ]] || continue

    name="$(basename "$dockerfile" .dockerfile)"
    image="${PACKAGE_NAME}/${name}"

    echo "Building $image from $dockerfile..."

    docker build \
        -f "$dockerfile" \
        -t "$image" \
        "$PACKAGE_DIR"
done