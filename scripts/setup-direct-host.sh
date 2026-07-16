#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    "Usage: $0 [--port PORT] [--bind IP] [--print-only]" \
    "Starts an authenticated Codex app-server listener for Codex Eink diagnostics." \
    "Use only on a private encrypted network; managed Remote co-control is a separate transport."
}

port=4500
bind_ip=""
print_only=0

while (($#)); do
  case "$1" in
    --port)
      port="${2:?missing port}"
      shift 2
      ;;
    --bind)
      bind_ip="${2:?missing bind address}"
      shift 2
      ;;
    --print-only)
      print_only=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$bind_ip" ]] && command -v tailscale >/dev/null; then
  bind_ip="$(tailscale ip -4 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$bind_ip" ]]; then
  printf 'No Tailscale IPv4 address found. Pass --bind with a private interface address.\n' >&2
  exit 1
fi
if [[ ! "$port" =~ ^[0-9]+$ ]] || ((port < 1024 || port > 65535)); then
  printf 'Port must be an integer from 1024 through 65535.\n' >&2
  exit 2
fi
is_safe_bind_address() {
  local address="$1"
  if [[ "$address" == "::1" || "$address" =~ ^[fF][dD]7[aA]:115[cC]:[aA]1[eE]0: ]]; then
    return 0
  fi
  if [[ "$address" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]]; then
    local first=$((10#${BASH_REMATCH[1]}))
    local second=$((10#${BASH_REMATCH[2]}))
    local third=$((10#${BASH_REMATCH[3]}))
    local fourth=$((10#${BASH_REMATCH[4]}))
    if ((first > 255 || second > 255 || third > 255 || fourth > 255)); then
      return 1
    fi
    ((first == 127 || (first == 100 && second >= 64 && second <= 127)))
    return
  fi
  return 1
}
if ! is_safe_bind_address "$bind_ip"; then
  printf 'Bind address must be loopback or a Tailscale IP address.\n' >&2
  exit 2
fi

command -v codex >/dev/null || {
  printf 'codex is not installed or not on PATH.\n' >&2
  exit 1
}

state_dir="${CODEX_HOME:-$HOME/.codex}/codex-eink"
token_file="$state_dir/app-server-token"
mkdir -p "$state_dir"
chmod 700 "$state_dir"
if [[ ! -s "$token_file" ]]; then
  umask 077
  if command -v openssl >/dev/null; then
    openssl rand -hex 32 > "$token_file"
  else
    printf 'openssl is required to generate a capability token.\n' >&2
    exit 1
  fi
fi
chmod 600 "$token_file"

endpoint_host="$bind_ip"
if [[ "$bind_ip" == *:* ]]; then
  endpoint_host="[$bind_ip]"
fi
endpoint="ws://$endpoint_host:$port"
printf 'Endpoint: %s\n' "$endpoint"
printf 'Token file: %s\n' "$token_file"
printf 'Copy the token into Codex Eink over a trusted channel. It will not be printed here.\n'

if ((print_only)); then
  printf 'Command: codex app-server --listen %s --ws-auth capability-token --ws-token-file %s\n' \
    "$endpoint" "$token_file"
  exit 0
fi

exec codex app-server \
  --listen "$endpoint" \
  --ws-auth capability-token \
  --ws-token-file "$token_file"
