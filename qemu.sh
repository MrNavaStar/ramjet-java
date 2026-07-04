#!/usr/bin/env bash
set -euo pipefail

IPXE_ROM="undionly.kpxe"
IPXE_URL="https://boot.ipxe.org/${IPXE_ROM}"

if [[ ! -f "$IPXE_ROM" ]]; then
  echo "[*] Downloading iPXE ROM..."
  curl -L -o "$IPXE_ROM" "$IPXE_URL"
fi

echo "[*] Starting QEMU..."

qemu-system-x86_64 \
  -m 1024 \
  -netdev user,id=n1,tftp=$(pwd),bootfile=qemu.ipxe \
  -device virtio-net-pci,netdev=n1 \
  -boot n \
  -option-rom "$IPXE_ROM"