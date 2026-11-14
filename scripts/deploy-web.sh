#!/usr/bin/env bash
# Builds the React app and publishes it to S3 + CloudFront.
# Usage: scripts/deploy-web.sh <stack-name> [region]
set -euo pipefail

STACK="${1:?stack name required}"
REGION="${2:-${AWS_REGION:-us-east-1}}"
cd "$(dirname "$0")/.."

output() {
  aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

BUCKET=$(output FrontendBucketName)
DISTRIBUTION=$(output DistributionId)

npm run build

# Hashed assets are immutable; index.html must always be revalidated so new releases are picked up.
aws s3 sync dist/assets "s3://$BUCKET/assets" --region "$REGION" \
  --cache-control "public,max-age=31536000,immutable"
aws s3 cp dist/index.html "s3://$BUCKET/index.html" --region "$REGION" \
  --cache-control "no-cache"

aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION" --paths "/index.html" >/dev/null
echo "Web app published to bucket $BUCKET and invalidated distribution $DISTRIBUTION"
