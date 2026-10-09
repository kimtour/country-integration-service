#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
context="${KUBE_CONTEXT:-docker-desktop}"
namespace=country-integration
baseline="${BASELINE_EXISTING_SCHEMA:-false}"
[[ "$baseline" == "true" || "$baseline" == "false" ]] || { echo "BASELINE_EXISTING_SCHEMA must be true or false" >&2; exit 1; }
replicas="${APP_REPLICAS:-2}"
image="${APP_IMAGE:-country-integration-service:$(python3 scripts/image-tag.py)}"
[[ "$replicas" =~ ^[1-9][0-9]*$ ]] || { echo "APP_REPLICAS must be a positive integer" >&2; exit 1; }
[[ -f .env ]] || { echo "Copy .env.example to .env and set both passwords first" >&2; exit 1; }
command -v python3 >/dev/null
kubectl --context="$context" cluster-info >/dev/null
docker build -t "$image" .
load_mode="${IMAGE_LOAD_MODE:-auto}"
if [[ "$load_mode" == auto ]]; then
  case "$context" in
    docker-desktop) load_mode=none ;;
    kind-*) load_mode=kind ;;
    minikube) load_mode=minikube ;;
    *) load_mode=registry ;;
  esac
fi
case "$load_mode" in
  none) ;;
  kind) kind load docker-image "$image" --name "${KIND_CLUSTER_NAME:-${context#kind-}}" ;;
  minikube) minikube image load "$image" --profile "${MINIKUBE_PROFILE:-minikube}" ;;
  registry)
    [[ "${PUSH_IMAGE:-false}" == true && -n "${APP_IMAGE:-}" ]] || {
      echo "Remote clusters require APP_IMAGE with a registry path and PUSH_IMAGE=true" >&2; exit 1;
    }
    docker push "$image" ;;
  *) echo "IMAGE_LOAD_MODE must be auto, none, kind, minikube or registry" >&2; exit 1 ;;
esac
kubectl --context="$context" create namespace "$namespace" --dry-run=client -o yaml |
  kubectl --context="$context" apply -f -
kubectl --context="$context" -n "$namespace" create secret generic database-credentials \
  --from-env-file=.env --dry-run=client -o yaml |
  kubectl --context="$context" -n "$namespace" apply -f -
kubectl --context="$context" apply -f k8s/mysql.yaml
kubectl --context="$context" -n "$namespace" rollout status deployment/mysql --timeout=300s
# Run migrations with one app process before starting more replicas.
kubectl --context="$context" create -f k8s/app.yaml --dry-run=client -o json |
  python3 -c 'import json,sys
text=sys.stdin.read(); decoder=json.JSONDecoder(); items=[]
while text.strip():
    obj,end=decoder.raw_decode(text.lstrip()); items.append(obj); text=text.lstrip()[end:]
for item in items:
    if item["kind"]=="Deployment":
        item["spec"]["replicas"]=1
        container=item["spec"]["template"]["spec"]["containers"][0]
        container["image"]=sys.argv[1]
        container["env"].append({"name":"DB_BASELINE_ON_MIGRATE","value":sys.argv[2]})
json.dump({"apiVersion":"v1","kind":"List","items":items},sys.stdout)' "$image" "$baseline" |
  kubectl --context="$context" apply -f -
# Existing Deployments also need replacement when the local image tag was rebuilt.
kubectl --context="$context" -n "$namespace" rollout restart deployment/country-app
kubectl --context="$context" -n "$namespace" rollout status deployment/country-app --timeout=900s
kubectl --context="$context" -n "$namespace" scale deployment/country-app --replicas="$replicas"
kubectl --context="$context" -n "$namespace" rollout status deployment/country-app --timeout=900s
kubectl --context="$context" -n "$namespace" get pods -o wide
kubectl --context="$context" -n "$namespace" get endpointslices \
  -l kubernetes.io/service-name=country-app
printf '\nFor API access: kubectl --context=%s -n %s port-forward service/country-app 8082:8080\n' "$context" "$namespace"
