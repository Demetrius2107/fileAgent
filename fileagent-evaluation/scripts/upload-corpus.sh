#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
corpus_dir="$script_dir/../src/main/resources/evaluation/v1/corpus"
base_url="${FILEAGENT_BASE_URL:-http://127.0.0.1:8080}"

curl --fail-with-body --silent --show-error \
  -X POST "$base_url/api/rag-files/upload" \
  -F "name=fileagent-eval-v1" \
  -F "tag=baseline" \
  -F "files=@$corpus_dir/employee-handbook.md" \
  -F "files=@$corpus_dir/expense-policy.md" \
  -F "files=@$corpus_dir/legacy-expense-policy.md" \
  -F "files=@$corpus_dir/security-policy.md" \
  -F "files=@$corpus_dir/product-manual.md" \
  -F "files=@$corpus_dir/sales-2025.csv" \
  -F "files=@$corpus_dir/sales-2026.csv"

printf '\n评测语料上传完成。\n'
