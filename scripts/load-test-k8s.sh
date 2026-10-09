#!/usr/bin/env bash
# Run a read-only sample through the internal Service, then remove this temporary pod.
set -euo pipefail
cd "$(dirname "$0")/.."
context="${KUBE_CONTEXT:-docker-desktop}"
namespace=country-integration
pod="country-read-load-$(date +%s)-$$"
output="${1:-target/load-results.json}"
mkdir -p "$(dirname "$output")"
manifest="$(mktemp)"
cleanup() {
  rm -f "$manifest"
  kubectl --context="$context" -n "$namespace" delete pod "$pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
trap cleanup EXIT
python3 - "$pod" > "$manifest" <<'PY'
import json,sys
from pathlib import Path
json.dump({'apiVersion':'v1','kind':'Pod','metadata':{'name':sys.argv[1],'namespace':'country-integration'},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,'containers':[{'name':'load','image':'python:3.13-alpine','command':['python','-c',Path('scripts/load-test.py').read_text(),'--url','http://country-app:8080/api/countries?page=0&size=50','--requests','200','--concurrency','8'],'resources':{'requests':{'cpu':'100m','memory':'64Mi'},'limits':{'cpu':'500m','memory':'128Mi'}}}]}},sys.stdout)
PY
kubectl --context="$context" apply -f "$manifest"
if ! kubectl --context="$context" -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$pod" --timeout=300s; then
  kubectl --context="$context" -n "$namespace" logs "$pod" || true
  exit 1
fi
kubectl --context="$context" -n "$namespace" logs "$pod" > "$output"
python3 - "$output" <<'PY'
import json,sys
with open(sys.argv[1]) as f: r=json.load(f)
assert r['statuses']=={'200':r['requests']},r
print(json.dumps(r,indent=2))
PY
