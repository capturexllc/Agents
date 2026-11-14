#!/usr/bin/env bash
# Uploads the agent prompt templates and runtime settings to the prompt bucket. The worker reads them through a
# cache (PROMPTS_CACHE_SECONDS, default 300), so an upload takes effect within that window, without a deploy.
#
# Usage: scripts/upload-prompts.sh <stack-name> [region]
# Layout in the bucket:  v1/prompts/<name>.md   and   v1/config/runtime.yml
set -euo pipefail

STACK="${1:?stack name required}"
REGION="${2:-${AWS_REGION:-ca-central-1}}"
cd "$(dirname "$0")/.."

BUCKET=$(aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='PromptBucketName'].OutputValue | [0]" --output text)
SOURCE="src/main/resources/prompts"

aws s3 sync "$SOURCE" "s3://$BUCKET/v1/prompts" --region "$REGION" \
  --exclude "runtime.yml" --cache-control "no-cache"
aws s3 cp "$SOURCE/runtime.yml" "s3://$BUCKET/v1/config/runtime.yml" --region "$REGION" \
  --cache-control "no-cache"
echo "Prompts uploaded to s3://$BUCKET/v1/"
