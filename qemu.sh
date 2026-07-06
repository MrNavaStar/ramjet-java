#!/usr/bin/env bash
set -euo pipefail

IPXE_ROM="undionly.kpxe"
IPXE_URL="https://boot.ipxe.org/${IPXE_ROM}"

# Download iPXE if missing
if [[ ! -f "$IPXE_ROM" ]]; then
  curl -L -o "$IPXE_ROM" "$IPXE_URL"
fi

# Download boot script from local instance of ramjet
ip=$(ip route get 1.1.1.1 | awk '{print $7; exit}')
cat > qemu.ipxe <<EOF
#!ipxe
chain http://${ip}:11722/v1/idle
EOF

qemu-system-x86_64 \
  -m 1024 \
  -netdev user,id=n1,tftp=$(pwd),bootfile=qemu.ipxe \
  -device virtio-net-pci,netdev=n1 \
  -boot n \
  -option-rom "$IPXE_ROM"