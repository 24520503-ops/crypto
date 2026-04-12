#!/usr/bin/env bash

set -euo pipefail

RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
PQC_ADDRESS="${PQC_ADDRESS:-0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d}"
TX_HASH="${1:-${TX_HASH:-}}"

rpc() {
  local method="$1"
  local params="$2"
  curl -s "$RPC_URL" \
    -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"${method}\",\"params\":${params},\"id\":1}"
}

pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
  fi
}

echo "== RPC URL =="
printf '%s\n\n' "$RPC_URL"

echo "== PQC Address =="
printf '%s\n\n' "$PQC_ADDRESS"

echo "== Chain ID =="
rpc "eth_chainId" "[]" | pretty
echo

echo "== Current Block =="
rpc "eth_blockNumber" "[]" | pretty
echo

echo "== Latest Nonce =="
rpc "eth_getTransactionCount" "[\"${PQC_ADDRESS}\",\"latest\"]" | pretty
echo

echo "== Pending Nonce =="
rpc "eth_getTransactionCount" "[\"${PQC_ADDRESS}\",\"pending\"]" | pretty
echo

echo "== Latest Balance =="
rpc "eth_getBalance" "[\"${PQC_ADDRESS}\",\"latest\"]" | pretty
echo

if [ -n "$TX_HASH" ]; then
  echo "== TX Hash =="
  printf '%s\n\n' "$TX_HASH"

  echo "== Transaction by hash =="
  rpc "eth_getTransactionByHash" "[\"${TX_HASH}\"]" | pretty
  echo

  echo "== Transaction receipt =="
  rpc "eth_getTransactionReceipt" "[\"${TX_HASH}\"]" | pretty
  echo
fi
