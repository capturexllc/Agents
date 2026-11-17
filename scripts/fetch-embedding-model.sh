#!/usr/bin/env bash
# Puts the open-source embedding model (all-MiniLM-L6-v2, ONNX) into the prompt bucket. The model is too large to ship
# inside the Lambda package, so the worker and ingest functions copy it from S3 to /tmp on cold start.
# Idempotent: does nothing when the files are already in the bucket.
#
# Usage: scripts/fetch-embedding-model.sh <stack-name> [region]
set -euo pipefail

STACK="${1:?stack name required}"
REGION="${2:-${AWS_REGION:-ca-central-1}}"
VERSION="1.23.0-beta33"
cd "$(dirname "$0")/.."

BUCKET=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='PromptBucketName'].OutputValue | [0]" --output text)
PREFIX="v1/models"

if aws s3api head-object --bucket "$BUCKET" --key "$PREFIX/all-minilm-l6-v2.onnx" --region "$REGION" >/dev/null 2>&1; then
  echo "Embedding model already present in s3://$BUCKET/$PREFIX/"
  exit 0
fi

WORK="build/model-fetch"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/model.jar" \
  "https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-embeddings-all-minilm-l6-v2/$VERSION/langchain4j-embeddings-all-minilm-l6-v2-$VERSION.jar"
unzip -q -j "$WORK/model.jar" all-minilm-l6-v2.onnx all-minilm-l6-v2-tokenizer.json -d "$WORK"
aws s3 cp "$WORK/all-minilm-l6-v2.onnx" "s3://$BUCKET/$PREFIX/all-minilm-l6-v2.onnx" --region "$REGION"
aws s3 cp "$WORK/all-minilm-l6-v2-tokenizer.json" "s3://$BUCKET/$PREFIX/all-minilm-l6-v2-tokenizer.json" --region "$REGION"
echo "Embedding model uploaded to s3://$BUCKET/$PREFIX/"
