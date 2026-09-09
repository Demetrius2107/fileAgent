#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
base_url="${FILEAGENT_BASE_URL:-http://127.0.0.1:8080}"
token="${FILEAGENT_EVALUATION_TOKEN:-}"
dataset_version="${FILEAGENT_EVALUATION_DATASET_VERSION:-v1}"
output_root="${FILEAGENT_EVALUATION_OUTPUT:-$repo_dir/target/evaluation}"
baseline_file="${FILEAGENT_EVALUATION_BASELINE:-}"
run_id="${FILEAGENT_EVALUATION_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"

if [[ -z "$token" ]]; then
  printf '缺少 FILEAGENT_EVALUATION_TOKEN。\n' >&2
  exit 1
fi
if [[ "$token" == *$'\n'* || "$token" == *$'\r'* ]]; then
  printf 'FILEAGENT_EVALUATION_TOKEN 不能包含换行符。\n' >&2
  exit 1
fi
command -v curl >/dev/null || { printf '缺少 curl。\n' >&2; exit 1; }
command -v jq >/dev/null || { printf '缺少 jq。\n' >&2; exit 1; }
if [[ ! "$dataset_version" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  printf 'FILEAGENT_EVALUATION_DATASET_VERSION 格式非法。\n' >&2
  exit 1
fi
if [[ ! "$run_id" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  printf 'FILEAGENT_EVALUATION_RUN_ID 格式非法。\n' >&2
  exit 1
fi

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
chmod 700 "$temp_dir"
header_file="$temp_dir/headers"
request_file="$temp_dir/request.json"
response_file="$temp_dir/response.json"
printf 'X-FileAgent-Evaluation-Token: %s\n' "$token" > "$header_file"
chmod 600 "$header_file"

if [[ -n "$baseline_file" ]]; then
  if [[ ! -f "$baseline_file" ]]; then
    printf 'baseline 文件不存在: %s\n' "$baseline_file" >&2
    exit 1
  fi
  jq -n --arg datasetVersion "$dataset_version" --slurpfile baseline "$baseline_file" \
    '{datasetVersion: $datasetVersion, baseline: $baseline[0]}' > "$request_file"
else
  jq -n --arg datasetVersion "$dataset_version" \
    '{datasetVersion: $datasetVersion}' > "$request_file"
fi

http_code="$(curl --silent --show-error \
  --output "$response_file" \
  --write-out '%{http_code}' \
  --request POST "${base_url%/}/internal/evaluation/rag/run" \
  --header "@$header_file" \
  --header 'Content-Type: application/json' \
  --data-binary "@$request_file")"

if [[ "$http_code" != "200" ]]; then
  message="$(jq -r '.message // empty' "$response_file" 2>/dev/null || true)"
  printf '评测请求失败: HTTP %s%s\n' "$http_code" "${message:+, $message}" >&2
  exit 1
fi
if [[ "$(jq -r '.code' "$response_file")" != "0" ]]; then
  jq -r '.message // "评测请求失败"' "$response_file" >&2
  exit 1
fi

dataset_output_root="$output_root/$dataset_version"
mkdir -p "$dataset_output_root"
run_dir="$dataset_output_root/$run_id"
suffix=2
while ! mkdir "$run_dir" 2>/dev/null; do
  run_dir="$dataset_output_root/$run_id-$suffix"
  suffix=$((suffix + 1))
done
jq '.data.report' "$response_file" > "$run_dir/report.json"
jq -r '.data.markdown' "$response_file" > "$run_dir/report.md"
jq -c '.data.observations[]' "$response_file" > "$run_dir/observations.jsonl"

printf '评测报告已保存到 %s\n' "$run_dir"
if [[ "$(jq -r '.data.report.gate.passed' "$response_file")" != "true" ]]; then
  jq -r '.data.report.gate.violations[]' "$response_file" >&2
  exit 2
fi

printf '质量门禁通过。\n'
