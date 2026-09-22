#!/usr/bin/env bash
set -euo pipefail

# 用法：upload-corpus.sh [数据集版本]，默认 v1；adaptive-v1/v2 使用独立知识库。
dataset_version="${1:-v1}"
case "$dataset_version" in
  v1)
    corpus_dir="src/main/resources/evaluation/v1/corpus"
    rag_name="fileagent-eval-v1"
    ;;
  adaptive-v1)
    corpus_dir="src/main/resources/evaluation/adaptive-v1/corpus"
    rag_name="fileagent-eval-adaptive-v1"
    ;;
  adaptive-v2)
    corpus_dir="src/main/resources/evaluation/adaptive-v2/corpus"
    rag_name="fileagent-eval-adaptive-v2"
    ;;
  *)
    printf '未知数据集版本: %s\n' "$dataset_version" >&2
    exit 1
    ;;
esac

script_dir="$(cd "$(dirname "$0")" && pwd)"
module_dir="$(cd "$script_dir/.." && pwd)"
base_url="${FILEAGENT_BASE_URL:-http://127.0.0.1:8080}"

if [[ ! -d "$module_dir/$corpus_dir" ]]; then
  printf '语料目录不存在: %s\n' "$module_dir/$corpus_dir" >&2
  exit 1
fi

file_args=()
for file in "$module_dir/$corpus_dir"/*; do
  file_args+=("-F" "files=@$file")
done

curl --fail-with-body --silent --show-error \
  -X POST "$base_url/api/rag-files/upload" \
  -F "name=$rag_name" \
  -F "tag=baseline" \
  "${file_args[@]}"

printf '\n评测语料上传完成（%s）。\n' "$dataset_version"
