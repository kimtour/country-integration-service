#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
output="${1:-target/backups/countrydb-$(date +%Y%m%d-%H%M%S).sql.gz}"
mkdir -p "$(dirname "$output")"
kubectl --context="${KUBE_CONTEXT:-docker-desktop}" -n country-integration exec deployment/mysql -- \
  sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --single-transaction --no-tablespaces -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE"' |
  gzip > "$output"
gzip -t "$output"
printf 'Database backup saved: %s\n' "$output"
