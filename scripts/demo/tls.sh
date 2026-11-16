#!/usr/bin/env bash
set -euo pipefail
# Operador: instalar previamente la CA de mkcert en el navegador de ensayo (mkcert -install).
# No instalar CA ni modificar trust stores automáticamente desde este script.
: "${ALE_DEMO_TLS_DIR:?Directorio nuevo absoluto fuera del repositorio}"
: "${ALE_DEMO_KEYSTORE_PASSWORD:?Secreto efimero para PKCS12}"
raiz_demo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
[[ "$ALE_DEMO_TLS_DIR" = /* ]] || { echo 'Se requiere ruta absoluta.' >&2; exit 1; }
destino_demo=$(realpath -m -- "$ALE_DEMO_TLS_DIR")
case "$destino_demo/" in "$raiz_demo/"*) echo 'TLS debe quedar fuera del repositorio.' >&2; exit 1 ;; esac
[[ ! -e "$destino_demo" ]] || { echo 'El directorio TLS ya existe; no sobrescribir.' >&2; exit 1; }
command -v mkcert >/dev/null
command -v openssl >/dev/null
umask 077
mkdir -p -- "$destino_demo"
mkcert -cert-file "$destino_demo/cert.pem" -key-file "$destino_demo/key.pem" localhost 127.0.0.1 ::1
ca_demo=$(mkcert -CAROOT)
openssl pkcs12 -export -in "$destino_demo/cert.pem" -inkey "$destino_demo/key.pem" \
  -certfile "$ca_demo/rootCA.pem" -name ale-demo -out "$destino_demo/localhost.p12" \
  -passout env:ALE_DEMO_KEYSTORE_PASSWORD
# La ruta, no los secretos, es la única salida propia.
printf 'ALE_DEMO_KEYSTORE=%s/localhost.p12\n' "$destino_demo"
