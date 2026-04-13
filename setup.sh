#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BESU_DIR="$ROOT_DIR/besu"
JAVA_HOME_DEFAULT="$ROOT_DIR/.local-jdk/jdk-21.0.10+7"
HELPER_BUILD_DIR="$ROOT_DIR/.build/pqc-signer"
HELPER_SOURCE="$ROOT_DIR/PqcSigner.java"
NETWORK_CONFIG_FILE="$ROOT_DIR/pqc-poa-config.json"
NETWORK_DIR="$ROOT_DIR/.besu-pqc-poa"
GENESIS_FILE="$NETWORK_DIR/genesis.json"
DATA_DIR="${DATA_DIR:-$ROOT_DIR/besu-pqc-poa-data}"
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

validator_key_file() {
  local validator_key

  validator_key="$(compgen -G "$NETWORK_DIR/keys/*/key.priv" | head -n 1 || true)"
  if [[ -z "$validator_key" ]]; then
    echo "Validator private key not found under $NETWORK_DIR/keys" >&2
    exit 1
  fi

  printf '%s\n' "$validator_key"
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

init_poa_network() {
  require_dir "$BESU_DIR/build/install/besu" "Built Besu distribution"

  if [[ -f "$GENESIS_FILE" ]] && compgen -G "$NETWORK_DIR/keys/*/key.priv" >/dev/null; then
    return
  fi

  rm -rf "$NETWORK_DIR"
  mkdir -p "$NETWORK_DIR"

  "$BESU_DIR/build/install/besu/bin/besu" operator generate-blockchain-config \
    --config-file="$NETWORK_CONFIG_FILE" \
    --to="$NETWORK_DIR"
}

run_besu() {
  local node_private_key_file

  require_dir "$BESU_DIR/build/install/besu" "Built Besu distribution"
  init_poa_network
  node_private_key_file="$(validator_key_file)"
  mkdir -p "$DATA_DIR"

  "$BESU_DIR/build/install/besu/bin/besu" \
    --genesis-file="$GENESIS_FILE" \
    --network-id=1337 \
    --node-private-key-file="$node_private_key_file" \
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
