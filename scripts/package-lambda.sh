#!/usr/bin/env bash
# Builds the Java backend and unpacks the standalone Lambda jar into build/lambda/.
# SAM deploys that directory (see CodeUri in infra/template.yaml).
set -euo pipefail

cd "$(dirname "$0")/.."

# clean: an incremental build can reuse a Spring Boot jar as the shading input.
mvn -B -q -DskipTests clean package

shaded=target/lambda/vamsdest-lambda.jar
if [ ! -f "$shaded" ]; then
  echo "Shaded Lambda jar not found at $shaded" >&2
  exit 1
fi
rm -rf build/lambda
mkdir -p build/lambda
unzip -q -o "$shaded" -d build/lambda

# Signed-jar metadata breaks when the jar is unpacked and repackaged.
rm -f build/lambda/META-INF/*.SF build/lambda/META-INF/*.RSA build/lambda/META-INF/*.DSA

for handler in com/vamsdest/lambda/ApiHandler.class com/vamsdest/lambda/WorkerHandler.class; do
  if [ ! -f "build/lambda/$handler" ]; then
    echo "Missing Lambda handler class: $handler" >&2
    exit 1
  fi
done

echo "Lambda code ready in build/lambda ($(find build/lambda -type f | wc -l) files)"
