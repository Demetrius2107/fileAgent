#!/usr/bin/env bash
set -euo pipefail

# Agent 离线评测入口：用固定 Judge 与评测夹具（resources/evaluation/agent-v1/）
# 跑 JUnit，验证「答案质量」与「Agent 行为」两类指标及质量门禁。
# 与 run-evaluation.sh（经 HTTP 调已部署实例）不同，本脚本不依赖运行中的服务、不调用真实模型。

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
mvn="${FILEAGENT_MVN:-mvn}"
dataset_version="${FILEAGENT_EVALUATION_DATASET_VERSION:-agent-v1}"

maven_args=()
if [[ -n "${FILEAGENT_MAVEN_SETTINGS:-}" ]]; then
  maven_args+=(-s "$FILEAGENT_MAVEN_SETTINGS")
fi
if [[ -n "${FILEAGENT_MAVEN_REPO:-}" ]]; then
  maven_args+=(-Dmaven.repo.local="$FILEAGENT_MAVEN_REPO")
fi

printf 'Agent 离线评测（JUnit，数据集 %s）\n' "$dataset_version"

"$mvn" -q -f "$repo_dir/pom.xml" \
  "${maven_args[@]}" \
  -pl fileagent-evaluation -am \
  test \
  -Dtest=AgentEvaluationRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false

printf 'Agent 评测通过（含质量门禁断言）。\n'
