#!/usr/bin/env bash
# Verifica que un APK de release está firmado con la keystore real, no con la
# debug key, antes de publicar una release (Google Play u otro destino).
#
# Uso: scripts/verify-release-signing.sh ruta/al/OpenTarifa-X.Y.Z.apk
set -euo pipefail

apk="${1:?Uso: $0 <ruta-al-apk>}"

if [ ! -f "$apk" ]; then
    echo "ERROR: no existe el archivo: $apk" >&2
    exit 1
fi

dn=$(apksigner verify --print-certs "$apk" | sed -n -E 's/.*certificate DN: (.*)/\1/p' | head -n1)

echo "APK: $apk"
echo "DN:  $dn"

if [[ "$dn" == *"CN=Android Debug"* ]]; then
    echo "ERROR: este APK está firmado con la debug key, NO con la keystore de release." >&2
    echo "No lo subas a GitHub Releases: rompería la actualización para usuarios existentes." >&2
    exit 1
fi

echo "OK: firmado con una key que no es la debug key."
