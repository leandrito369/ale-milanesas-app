#!/usr/bin/env bash
set -euo pipefail
# Ejecutar desde cualquier directorio; no sourcear archivos externos con secretos.
raiz_demo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$raiz_demo"
export COMPOSE_PROJECT_NAME=ale-b5-demo
export ALE_DB_NAME=ale_milanesas_demo ALE_DB_USER=ale_demo ALE_DB_HOST=127.0.0.1 ALE_DB_PORT=55439
: "${ALE_DB_PASSWORD:?Definir secreto efimero de DB en entorno}"
case "${1:-}" in
  db) docker compose -f deploy/demo/compose.yaml up -d --wait ;;
  teardown) docker compose -f deploy/demo/compose.yaml down --volumes ;;
  reset) docker compose -f deploy/demo/compose.yaml down --volumes
         docker compose -f deploy/demo/compose.yaml up -d --wait ;;
  run)
    : "${ALE_DEMO_KEYSTORE:?Archivo PKCS12 fuera de Git con certificado confiable}"
    : "${ALE_DEMO_KEYSTORE_PASSWORD:?Secreto efimero TLS}"
    [[ -f "$ALE_DEMO_KEYSTORE" ]] || exit 1
    export SPRING_PROFILES_ACTIVE=demo
    export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8443
    export SERVER_SSL_ENABLED=true SERVER_SSL_KEY_STORE="file:$ALE_DEMO_KEYSTORE"
    export SERVER_SSL_KEY_STORE_TYPE=PKCS12 SERVER_SSL_KEY_STORE_PASSWORD="$ALE_DEMO_KEYSTORE_PASSWORD"
    export ALE_WEB_ALLOWED_ORIGIN=https://localhost:8443
    export ALE_HTTP_WRITES_PEDIDOS_UC03_ENABLED=true ALE_HTTP_WRITES_PEDIDOS_UC43_ENABLED=true
    export SERVER_SERVLET_SESSION_COOKIE_SECURE=true SERVER_SERVLET_SESSION_COOKIE_HTTP_ONLY=true
    export SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=strict
    jar_demo=target/ale-milanesas-app-0.1.0-SNAPSHOT.jar
    [[ -f "$jar_demo" ]] || { echo 'Falta JAR: ejecutar mvn clean verify.' >&2; exit 1; }
    git rev-parse HEAD
    exec java -jar "$jar_demo"
    ;;
  seed)
    # Aplicar Flyway V1–V3 con el primer arranque antes de ejecutar seed.
    : "${ALE_DEMO_CREDENTIAL:?Credencial efimera de usuarios sinteticos, fuera de Git}"
    : "${MAVEN_CMD:=mvn}"
    "$MAVEN_CMD" -B -ntp dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt
    export ALE_DEMO_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/ale_milanesas_demo
    java --class-path "$(cat target/demo-classpath.txt)" scripts/demo/Bootstrap.java
    ;;
  *) echo 'Uso: entorno.sh db|run|seed|reset|teardown' >&2; exit 2 ;;
esac
