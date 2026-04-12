#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BESU_DIR="$ROOT_DIR/besu"
JAVA_HOME_DEFAULT="$ROOT_DIR/.local-jdk/jdk-21.0.10+7"
DATA_DIR="$ROOT_DIR/besu-pqc-data"
HELPER_BUILD_DIR="$ROOT_DIR/.build/pqc-signer"
HELPER_SOURCE="$ROOT_DIR/PqcSigner.java"
RPC_PORT="${RPC_PORT:-8545}"
P2P_PORT="${P2P_PORT:-30303}"

if [[ -d "$JAVA_HOME_DEFAULT" ]]; then
  export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

require_dir() {
  local dir_path="$1"
  local label="$2"

  if [[ ! -d "$dir_path" ]]; then
    echo "$label not found at $dir_path" >&2
    exit 1
  fi
}

require_cmd() {
  local command_name="$1"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required command: $command_name" >&2
    exit 1
  fi
}

compile_helper() {
  require_cmd javac
  mkdir -p "$HELPER_BUILD_DIR"

  javac \
    -cp "$BESU_DIR/build/install/besu/lib/*" \
    -d "$HELPER_BUILD_DIR" \
    "$HELPER_SOURCE"
}

build_besu() {
  require_dir "$BESU_DIR" "Besu source directory"
  require_cmd bash
  "$BESU_DIR/gradlew" -p "$BESU_DIR" installDist
  compile_helper
}

run_besu() {
  require_dir "$BESU_DIR/build/install/besu" "Built Besu distribution"
  mkdir -p "$DATA_DIR"
  "$BESU_DIR/build/install/besu/bin/besu" \
    --network=dev \
    --data-path="$DATA_DIR" \
    --rpc-http-enabled \
    --rpc-http-host=0.0.0.0 \
    --rpc-http-port="$RPC_PORT" \
    --rpc-http-api=ETH,NET,WEB3 \
    --host-allowlist='*' \
    --min-gas-price=0 \
    --p2p-port="$P2P_PORT"
}

case "${1:-all}" in
  build)
    build_besu
    ;;
  run)
    run_besu
    ;;
  all)
    build_besu
    run_besu
    ;;
  *)
    echo "Usage: $0 [build|run|all]"
    exit 1
    ;;
esac
