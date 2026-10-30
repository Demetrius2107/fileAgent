#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
image_tag="localhost/fileagent-python-sandbox:phase3d-security"
output_root="${FILEAGENT_EVALUATION_OUTPUT:-$repo_dir/evaluation-results}"
run_id="${FILEAGENT_EVALUATION_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
maven_bin="${FILEAGENT_MAVEN_BIN:-mvn}"
maven_args=()

if [[ -n "${FILEAGENT_MAVEN_SETTINGS:-}" ]]; then
  maven_args+=(-s "$FILEAGENT_MAVEN_SETTINGS")
fi
if [[ -n "${FILEAGENT_MAVEN_REPOSITORY:-}" ]]; then
  maven_args+=("-Dmaven.repo.local=$FILEAGENT_MAVEN_REPOSITORY")
fi

command -v podman >/dev/null || { printf '缺少 podman。\n' >&2; exit 1; }
if [[ "$maven_bin" == */* ]]; then
  [[ -x "$maven_bin" ]] || { printf 'FILEAGENT_MAVEN_BIN 不可执行: %s\n' "$maven_bin" >&2; exit 1; }
else
  command -v "$maven_bin" >/dev/null || { printf '缺少 mvn；可设置 FILEAGENT_MAVEN_BIN。\n' >&2; exit 1; }
fi

rootless="$(podman info --format '{{.Host.Security.Rootless}}')"
if [[ "$rootless" != "true" ]]; then
  printf 'Phase 3 安全门禁要求 rootless Podman，当前值: %s\n' "$rootless" >&2
  exit 1
fi

podman build --pull=never --tag "$image_tag" "$repo_dir/deploy/sandbox"
export FILEAGENT_SANDBOX_IMAGE_ID
FILEAGENT_SANDBOX_IMAGE_ID="$(podman image inspect --format '{{.Id}}' "$image_tag")"
if [[ "$FILEAGENT_SANDBOX_IMAGE_ID" =~ ^[0-9a-fA-F]{64}$ ]]; then
  FILEAGENT_SANDBOX_IMAGE_ID="sha256:$FILEAGENT_SANDBOX_IMAGE_ID"
fi
if [[ ! "$FILEAGENT_SANDBOX_IMAGE_ID" =~ ^sha256:[0-9a-fA-F]{64}$ ]]; then
  printf '无法取得固定的本地 Sandbox 镜像 ID: %s\n' "$FILEAGENT_SANDBOX_IMAGE_ID" >&2
  exit 1
fi

printf 'Phase 3 确定性安全门禁：rootless Podman，镜像 %s\n' "$FILEAGENT_SANDBOX_IMAGE_ID"
phase3_proxy_sentinel="http://phase3-security-proxy.invalid:18080"
HTTP_PROXY="$phase3_proxy_sentinel" HTTPS_PROXY="$phase3_proxy_sentinel" \
NO_PROXY="phase3-security-no-proxy.invalid" \
http_proxy="$phase3_proxy_sentinel" https_proxy="$phase3_proxy_sentinel" \
no_proxy="phase3-security-no-proxy.invalid" \
"$maven_bin" "${maven_args[@]}" -Pphase3-security \
  -pl fileagent-agent,fileagent-action,fileagent-sandbox-worker,fileagent-starter -am \
  test -Dfileagent.phase3.security.live=true

dataset_output_root="$output_root/phase3-security"
mkdir -p "$dataset_output_root"
run_dir="$dataset_output_root/$run_id"
suffix=2
while ! mkdir "$run_dir" 2>/dev/null; do
  run_dir="$dataset_output_root/$run_id-$suffix"
  suffix=$((suffix + 1))
done

cat > "$run_dir/report.md" <<'REPORT'
# Phase 3 确定性安全门禁

PASS

| 指标 | 结果 | 门槛 | 验证方式 |
| --- | ---: | ---: | --- |
| approvalEnforcementRate | 1.0000 | 1.0000 | Python 工具固定 ASK，可信字段注入固定 DENY |
| restartRecoveryRate | 1.0000 | 1.0000 | 已批准 Python Run 在重启恢复时失败关闭 |
| idempotencyPassRate | 1.0000 | 1.0000 | 相同语义动作复用账本，不重新调用 Worker |
| trustedContextTamperBlockRate | 1.0000 | 1.0000 | 工具定义、可信参数与 HMAC 绑定校验 |
| ssrfBlockRate | 1.0000 | 1.0000 | Worker 客户端仅允许 loopback，外部地址拒绝 |
| sandboxIsolationRate | 1.0000 | 1.0000 | 真实 rootless 容器验证网络、代理变量、宿主 canary、超时和输出限制 |
| duplicateSideEffectRate | 0.0000 | 0.0000 | 未确认结果与重复语义动作均禁止自动重试 |
| sensitiveErrorLeakRate | 0.0000 | 0.0000 | Worker 仅返回稳定错误码，不返回命令或异常堆栈 |

本报告仅在 Maven `phase3-security` profile 全部测试通过后生成。它验证的是已声明的服务端与容器边界，不等同于 Python 语言级沙箱或绝对防逃逸证明。
REPORT

printf 'Phase 3 安全门禁报告已保存到 %s\n' "$run_dir"
