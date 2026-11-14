#!/usr/bin/env bash
# Uploads policy and rule documents (PDF, XLSX) from docs/rag/ to the corpus prefix. Each upload triggers the ingest
# function, which chunks and embeds the document and replaces its earlier chunks.
#
# Usage: scripts/upload-policies.sh <stack-name> [region] [file ...]
set -euo pipefail

STACK="${1:?stack name required}"
REGION="${2:-${AWS_REGION:-ca-central-1}}"
shift $(( $# >= 2 ? 2 : $# )) || true
cd "$(dirname "$0")/.."

BUCKET=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='PromptBucketName'].OutputValue | [0]" --output text)

FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=(docs/rag/*.pdf docs/rag/*.xlsx)
fi
for file in "${FILES[@]}"; do
  [ -f "$file" ] || continue
  aws s3 cp "$file" "s3://$BUCKET/v1/rag/$(basename "$file")" --region "$REGION"
done
echo "Uploaded ${#FILES[@]} document(s) to s3://$BUCKET/v1/rag/. Ingest runs on each upload."
