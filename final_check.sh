#!/usr/bin/env bash

set -euo pipefail

RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
PQC_ADDRESS="${PQC_ADDRESS:-0x7a4e1bd68b8aea6c04f0452cbfef26ba22e2234d}"
COMMAND="${1:-status}"
ARG="${2:-}"
WAIT_SECONDS="${WAIT_SECONDS:-20}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"

rpc() {
  local method="$1"
  local params="$2"
  curl -s "$RPC_URL" \
    -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"${method}\",\"params\":${params},\"id\":1}"
}

extract_result() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.result'
  else
    tr -d '\n' | sed -E 's/^.*"result":("[^"]*"|null|\{.*\}|\[.*\]).*$/\1/'
  fi
}

pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
  fi
}

pretty_compact() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
  fi
}

hex_to_dec() {
  local value="$1"
  if [[ -z "$value" || "$value" == "null" ]]; then
    printf 'null\n'
    return
  fi
  printf '%s\n' "$((value))"
}

get_result() {
  rpc "$1" "$2" | extract_result | tr -d '"'
}

tx_field() {
  local json="$1"
  local field="$2"

  if command -v jq >/dev/null 2>&1; then
    jq -r --arg field "$field" '.result[$field] // "null"' <<<"$json"
  else
    sed -nE "s/.*\"${field}\":\"?([^\",}]*)\"?.*/\1/p" <<<"$(printf '%s' "$json" | tr -d '\n')" | head -n 1
  fi
}

print_tx_summary() {
  local tx_json="$1"
  local receipt_json="$2"
  local from to nonce value gas gas_price block status input pqc_public_key pqc_signature

  from="$(tx_field "$tx_json" "from")"
  to="$(tx_field "$tx_json" "to")"
  nonce="$(tx_field "$tx_json" "nonce")"
  value="$(tx_field "$tx_json" "value")"
  gas="$(tx_field "$tx_json" "gas")"
  gas_price="$(tx_field "$tx_json" "gasPrice")"
  block="$(tx_field "$tx_json" "blockNumber")"
  input="$(tx_field "$tx_json" "input")"
  pqc_public_key="$(tx_field "$tx_json" "pqcPublicKey")"
  pqc_signature="$(tx_field "$tx_json" "pqcSignature")"
  status="$(tx_field "$receipt_json" "status")"

  printf '\n== Transaction Summary ==\n'
  printf 'from: %s\n' "$from"
  printf 'to: %s\n' "$to"
  printf 'nonce: %s\n' "$nonce"
  printf 'value: %s\n' "$value"
  printf 'gas: %s\n' "$gas"
  printf 'gasPrice: %s\n' "$gas_price"
  printf 'blockNumber: %s\n' "$block"
  printf 'status: %s\n' "$status"
  printf 'input: %s\n' "$input"
  printf 'pqcPublicKey: %s\n' "$pqc_public_key"
  printf 'pqcSignature: %s\n' "$pqc_signature"
}

block_tag() {
  local value="$1"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    printf '0x%x\n' "$value"
  else
    printf '%s\n' "$value"
  fi
}

print_overview() {
  local chain_id block latest_nonce pending_nonce balance
  chain_id="$(get_result "eth_chainId" "[]")"
  block="$(get_result "eth_blockNumber" "[]")"
  latest_nonce="$(get_result "eth_getTransactionCount" "[\"${PQC_ADDRESS}\",\"latest\"]")"
  pending_nonce="$(get_result "eth_getTransactionCount" "[\"${PQC_ADDRESS}\",\"pending\"]")"
  balance="$(get_result "eth_getBalance" "[\"${PQC_ADDRESS}\",\"latest\"]")"

  printf 'RPC URL: %s\n' "$RPC_URL"
  printf 'PQC sender: %s\n' "$PQC_ADDRESS"
  printf 'Chain ID: %s\n' "$(hex_to_dec "$chain_id")"
  printf 'Current block: %s\n' "$(hex_to_dec "$block")"
  printf 'Latest nonce: %s\n' "$(hex_to_dec "$latest_nonce")"
  printf 'Pending nonce: %s\n' "$(hex_to_dec "$pending_nonce")"
  printf 'Latest balance (wei): %s\n' "$(hex_to_dec "$balance")"
}

wait_for_block_growth() {
  local start_block current_block elapsed
  start_block="$(get_result "eth_blockNumber" "[]")"
  elapsed=0

  printf 'Watching for a new block for up to %ss...\n' "$WAIT_SECONDS"

  while (( elapsed < WAIT_SECONDS )); do
    sleep "$POLL_INTERVAL"
    elapsed=$((elapsed + POLL_INTERVAL))
    current_block="$(get_result "eth_blockNumber" "[]")"
    if [[ "$current_block" != "$start_block" ]]; then
      printf 'New block detected: %s -> %s\n' "$(hex_to_dec "$start_block")" "$(hex_to_dec "$current_block")"
      return 0
    fi
  done

  printf 'No new block detected. Block is still %s\n' "$(hex_to_dec "$start_block")"
  return 1
}

print_tx_status() {
  local tx_hash="$1"
  local tx_json receipt_json

  tx_json="$(rpc "eth_getTransactionByHash" "[\"${tx_hash}\"]")"
  receipt_json="$(rpc "eth_getTransactionReceipt" "[\"${tx_hash}\"]")"

  printf '\nTransaction hash: %s\n' "$tx_hash"
  print_tx_summary "$tx_json" "$receipt_json"
  printf '\n== eth_getTransactionByHash ==\n'
  printf '%s\n' "$tx_json" | pretty_compact
  printf '\n== eth_getTransactionReceipt ==\n'
  printf '%s\n' "$receipt_json" | pretty_compact
}

print_block_by_number() {
  local block_number
  block_number="$(block_tag "$1")"
  printf '== eth_getBlockByNumber(%s) ==\n' "$block_number"
  rpc "eth_getBlockByNumber" "[\"${block_number}\", true]" | pretty
}

dump_full_chain() {
  local latest latest_dec current
  latest="$(get_result "eth_blockNumber" "[]")"
  latest_dec="$(hex_to_dec "$latest")"

  printf 'Dumping blocks 0..%s\n' "$latest_dec"
  current=0
  while (( current <= latest_dec )); do
    print_block_by_number "$current"
    printf '\n'
    current=$((current + 1))
  done
}

usage() {
  cat <<'EOF'
Usage:
  bash final_check.sh status
  bash final_check.sh tx <txHash>
  bash final_check.sh block <blockNumber|latest|earliest|pending>
  bash final_check.sh chain

Commands:
  status   Show chain overview and wait for next block
  tx       Show transaction and receipt by hash
  block    Show one full block with transactions
  chain    Dump all blocks from genesis to latest
EOF
}

case "$COMMAND" in
  status)
    print_overview
    wait_for_block_growth || true
    ;;
  tx)
    if [[ -z "$ARG" ]]; then
      usage
      exit 1
    fi
    print_overview
    print_tx_status "$ARG"
    printf '\n'
    wait_for_block_growth || true
    printf '\n== Re-check after waiting ==\n'
    print_tx_status "$ARG"
    ;;
  block)
    if [[ -z "$ARG" ]]; then
      usage
      exit 1
    fi
    print_block_by_number "$ARG"
    ;;
  chain)
    dump_full_chain
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    if [[ "$COMMAND" == 0x* ]]; then
      print_overview
      print_tx_status "$COMMAND"
      printf '\n'
      wait_for_block_growth || true
      printf '\n== Re-check after waiting ==\n'
      print_tx_status "$COMMAND"
    else
      usage
      exit 1
    fi
    ;;
esac
