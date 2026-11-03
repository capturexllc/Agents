# VamsDest

VamsDest is a Java/Spring Boot travel-planning service with three parallel research agents, a React interface, replayable server-sent events, and optional Claude summaries through Amazon Bedrock.

## Local Development

Requirements: Java 21+, Maven 3.9+, Node.js 22+, and npm.

Run the backend and frontend in separate terminals from the repository root:

```powershell
mvn spring-boot:run
```

```powershell
npm install
npm run dev
```

Open the Vite URL printed by npm, normally `http://localhost:5173`. The default `SEARCH_MODE=demo` is deliberately non-live; it returns an explicit demo notice and does not claim to check actual travel data.

For live search, set `SEARCH_MODE=tavily` and `TAVILY_API_KEY`. For Claude summaries, set `BEDROCK_ENABLED=true`, `AWS_REGION`, and optionally `BEDROCK_MODEL_ID`. Authenticate with the AWS SDK's normal credential chain, such as an AWS profile locally or an IAM role in AWS. Enable the selected model in the target Bedrock region. Never put credentials in source, frontend variables, or committed files.

Example PowerShell setup for one backend terminal:

```powershell
$env:SEARCH_MODE = "tavily"
$env:TAVILY_API_KEY = "set-this-in-your-local-secret-manager"
$env:BEDROCK_ENABLED = "true"
$env:AWS_REGION = "us-east-1"
mvn spring-boot:run
```

The browser UI sends requests through Vite's `/api` proxy. With live search configured, the three agents search concurrently. Without Bedrock, live search results are shown as unsummarized source excerpts. Travel listings are research leads, not guaranteed booking inventory or confirmed prices.

## AWS Bedrock

The service uses the AWS SDK for Java v2 Bedrock Runtime `Converse` API. The default is the US geographic inference profile `us.anthropic.claude-sonnet-5-5`; set `BEDROCK_MODEL_ID` to an available model or inference profile that fits the account's cost, latency, and data-residency requirements. Model access, regions, quotas, and pricing are controlled by AWS and vary by account. The application does not silently switch models. Grant the workload identity only the Bedrock invoke permissions needed for the selected model or inference profile. Prefer EKS Pod Identity or IRSA over static AWS keys. Current profile and lifecycle details are listed in the [AWS Bedrock model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-5-5.html).

Live web research uses Tavily. Weather, event, hotel, flight, and rental-car findings depend on what the configured web-search provider returns. For guaranteed forecasts or bookable rates, integrate contracted domain-specific APIs behind `TravelResearchProvider`; no booking or purchase action is implemented.

## Container

```powershell
docker build -t vamsdest:0.1.0 .
docker run --rm -p 8080:8080 -e SEARCH_MODE=demo vamsdest:0.1.0
```

The container builds the React assets into the Spring Boot jar and runs as UID 10001. Production builds enable the sign-in link by default. The `prod` Spring profile requires OIDC configuration and secures API requests with an OIDC login session and CSRF protection.

## EKS Deployment

`deploy/k8s` contains a Kustomize deployment, restricted namespace, non-root read-only container settings, probes, resource limits, HTTPS ALB ingress, and an AWS WAF association placeholder.

Before applying it, configure:

- An EKS cluster, AWS Load Balancer Controller, ACM certificate, DNS hostname, and AWS WAFv2 ACL.
- An EKS Pod Identity association for service account `vamsdest` with least-privilege `bedrock:InvokeModel` access.
- A published Amazon Bedrock Guardrail and version configured in `deploy/k8s/configmap.yaml`; production startup requires both values.
- An External Secrets Operator (or equivalent) to create Kubernetes Secret `vamsdest-runtime` with `TAVILY_API_KEY`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, and `OIDC_CLIENT_SECRET`. Keep secret values in AWS Secrets Manager; do not commit a Kubernetes Secret manifest.
- The ECR image URI/tag, ingress hostname, certificate ARN, WAF ACL ARN, AWS region, and Bedrock model ID in the deployment configuration.
- Encrypted Kubernetes secret storage, cluster access controls, image scanning/signing, backups, alerting, and an operational runbook for the target environment.

Build and push the image to ECR, update the image and placeholders in `deploy/k8s`, then apply with:

```powershell
kubectl apply -k deploy/k8s
kubectl rollout status deployment/vamsdest -n vamsdest
```

`kubectl` and AWS deployment credentials are not included in this repository. The ALB/WAF placeholders must be replaced before deployment; the manifests are not a turnkey AWS account provisioning plan.

## Verification

```powershell
mvn test
npm run build
```

The current run registry is in-memory and retains trip data for up to one hour. The deployment intentionally uses one replica: reconnect/replay works within one process, but restarts lose active runs, and multiple replicas cannot share trip events. Before enabling HA or autoscaling, move run state/events to shared durable storage and use a shared event broker/job queue. Production rollout also requires validating OIDC, AWS IAM, WAF, TLS, quotas, provider contracts, and operations in the target account.