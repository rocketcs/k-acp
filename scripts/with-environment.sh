#!/usr/bin/env bash
# Load one operator-local environment file, then run exactly one command.
set -euo pipefail

usage() {
  echo "Usage: $0 <local|test|prod> [--require ssh|mysql] -- <command> [args...]" >&2
  exit 64
}

[[ $# -ge 3 ]] || usage

environment="$1"
case "$environment" in
  local|test|prod) ;;
  *) usage ;;
esac

shift
requirement=""
if [[ "${1:-}" == "--require" ]]; then
  [[ $# -ge 4 ]] || usage
  requirement="$2"
  case "$requirement" in
    ssh|mysql) ;;
    *) usage ;;
  esac
  shift 2
fi
[[ "${1:-}" == "--" ]] || usage
shift
[[ $# -ge 1 ]] || usage

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
environment_file="$repository_root/env/$environment/.env"

if [[ ! -f "$environment_file" ]]; then
  echo "Environment file not found: $environment_file" >&2
  echo "Create it from $repository_root/env/$environment/.env.example" >&2
  exit 66
fi

set -a
# shellcheck disable=SC1090
source "$environment_file"
set +a

if [[ -z "${KACP_ENV:-}" ]]; then
  echo "Missing required value 'KACP_ENV' in $environment_file" >&2
  exit 65
fi

if [[ "$KACP_ENV" != "$environment" ]]; then
  echo "KACP_ENV must be '$environment' in $environment_file" >&2
  exit 65
fi

if [[ "$requirement" == "mysql" ]]; then
  for required_key in MYSQL_HOST MYSQL_PORT MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_SSL_MODE; do
    value="${!required_key:-}"
    if [[ -z "$value" || "$value" == *CHANGEME* ]]; then
      echo "Missing required value '$required_key' in $environment_file" >&2
      exit 65
    fi
  done
fi

if [[ "$requirement" == "ssh" && "$environment" != "local" ]]; then
  for required_key in SSH_HOST SSH_PORT SSH_USER; do
    value="${!required_key:-}"
    if [[ -z "$value" || "$value" == *CHANGEME* ]]; then
      echo "Missing required value '$required_key' in $environment_file" >&2
      exit 65
    fi
  done
  if [[ -n "${SSH_IDENTITY_FILE:-}" ]]; then
    [[ -f "$SSH_IDENTITY_FILE" ]] || {
      echo "SSH identity file not found: $SSH_IDENTITY_FILE" >&2
      exit 65
    }
  elif [[ -z "${SSH_PASSWORD:-}" || "$SSH_PASSWORD" == *CHANGEME* ]]; then
    echo "Set either SSH_IDENTITY_FILE or SSH_PASSWORD in $environment_file" >&2
    exit 65
  fi
fi

if [[ "$requirement" == "mysql" && "$environment" == "local" ]]; then
  [[ "$MYSQL_SSL_MODE" == "DISABLED" ]] || {
    echo "Local MySQL must use MYSQL_SSL_MODE=DISABLED" >&2
    exit 65
  }
fi

if [[ "$requirement" == "mysql" && "$environment" != "local" ]]; then
  case "$MYSQL_SSL_MODE" in
    DISABLED) ;;
    VERIFY_CA|VERIFY_IDENTITY)
      [[ -n "${MYSQL_SSL_CA:-}" && "$MYSQL_SSL_CA" != *CHANGEME* ]] || {
        echo "Missing required value 'MYSQL_SSL_CA' in $environment_file" >&2
        exit 65
      }
      [[ -f "$MYSQL_SSL_CA" ]] || {
        echo "MySQL CA file not found: $MYSQL_SSL_CA" >&2
        exit 65
      }
      ;;
    *)
      echo "MYSQL_SSL_MODE must be DISABLED, VERIFY_CA, or VERIFY_IDENTITY" >&2
      exit 65
      ;;
  esac
fi

exec "$@"
