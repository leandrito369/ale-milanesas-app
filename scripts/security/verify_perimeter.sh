#!/usr/bin/env bash
# B4-02D: verifica sintaxis TLS y rate-limit Nginx en loopback (no certifica despliegue real).
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
name="ale-b402d-nginx-ci-$$"
backend_pid=""
finish() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" >/dev/null 2>&1 || true; fi
  rm -rf "$tmp"
}
trap finish EXIT
mkdir -p "$tmp/tls" "$tmp/html"
printf 'ALE_UPSTREAM_OK\n' > "$tmp/html/index.html"
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -keyout "$tmp/tls/privkey.pem" -out "$tmp/tls/fullchain.pem" \
  -subj "/CN=ale-ci.local" -addext "subjectAltName=DNS:ale-ci.local" >/dev/null 2>&1
sed -e 's/@ALE_HOST@/ale-ci.local/g' \
    -e 's/listen 80 default_server;/listen 18080 default_server;/g' \
    -e 's/listen 443 ssl default_server;/listen 18443 ssl default_server;/g' \
    -e 's#http://127.0.0.1:8080#http://127.0.0.1:18081#g' \
    "$repo/deploy/nginx/ale-milanesas.nginx.conf.template" > "$tmp/nginx.conf"
docker run --rm --network host -v "$tmp/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$tmp/tls:/etc/ale/tls:ro" nginx:stable-alpine nginx -t
python3 -m http.server 18081 --bind 127.0.0.1 --directory "$tmp/html" \
  > "$tmp/backend.log" 2>&1 &
backend_pid=$!
docker run -d --name "$name" --network host \
  -v "$tmp/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$tmp/tls:/etc/ale/tls:ro" nginx:stable-alpine >/dev/null
for _ in $(seq 1 25); do
  if curl --silent --fail --noproxy '*' --connect-timeout 1 \
     --cacert "$tmp/tls/fullchain.pem" \
     --resolve ale-ci.local:18443:127.0.0.1 \
     https://ale-ci.local:18443/ > "$tmp/response"; then break; fi
  sleep 1
done
grep -q 'ALE_UPSTREAM_OK' "$tmp/response"
echo "TLS verificado con certificado CI y proxy loopback"
code="$(curl --silent --noproxy '*' -o /dev/null -w '%{http_code}' \
  --cacert "$tmp/tls/fullchain.pem" \
  --resolve ale-ci.local:18443:127.0.0.1 \
  -H 'Host: host-falso.invalid' https://ale-ci.local:18443/)"
[[ "$code" == "421" ]] || { echo "Host spoof no rechazado ($code)"; exit 1; }
echo "Host no canónico rechazado"
# Varias solicitudes POST sintéticas en ráfaga deben activar la zona compartida.
rejected=0
for _ in $(seq 1 18); do
  code="$(curl --silent --noproxy '*' -o /dev/null -w '%{http_code}' \
    --cacert "$tmp/tls/fullchain.pem" \
    --resolve ale-ci.local:18443:127.0.0.1 \
    -H 'Content-Type: application/json' \
    --data '{"nombreUsuario":"ficticio","credencial":"dummy"}' \
    https://ale-ci.local:18443/api/v1/auth/login)"
  if [[ "$code" == "429" ]]; then rejected=$((rejected+1)); fi
done
(( rejected > 0 )) || { echo "Nginx no limito solicitudes POST"; exit 1; }
echo "Rate-limit Nginx activo: $rejected de 18 solicitudes sintéticas rechazadas 429"
echo "PASS: proxy TLS local; falta verificar certificado, DNS, firewall y CDN reales"
