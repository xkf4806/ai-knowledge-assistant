#!/usr/bin/env bash
#
# 第 4 周：导入示例文档，并对比三种检索模式的 RAG 指标。
#
# 默认只跑检索评测，不调用对话模型：
#   ./scripts/evaluate-rag.sh
#
# 如需连同答案准确率一起评测（会逐题调用对话模型）：
#   AIKB_EVAL_ANSWERS=true ./scripts/evaluate-rag.sh
#
set -euo pipefail

BASE_URL="${AIKB_BASE_URL:-http://localhost:8080}"
INCLUDE_ANSWERS="${AIKB_EVAL_ANSWERS:-false}"

curl -fsS -X POST "${BASE_URL}/api/rag/ingest" >/dev/null

body="{\"modes\":[\"vector\",\"hybrid\",\"hybrid-rerank\"],\"includeAnswers\":${INCLUDE_ANSWERS}}"
result="$(curl -fsS -X POST "${BASE_URL}/api/rag/eval" \
  -H "Content-Type: application/json" \
  -d "${body}")"

if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "${result}" | jq
else
  printf '%s\n' "${result}"
fi
