# VamsDest

VamsDest is a Java/Spring Boot travel-planning service built around an agentic harness. Four specialist Amazon Nova agents (Destination, Stay & Activities, Flights, and Taxi & Car) run in parallel on Amazon Bedrock. The Taxi & Car agent only researches taxis, ride-hail, airport transfers, and rental cars. Each agent has a live `web_search` tool backed by Tavily: it chooses its own queries, reads the results, searches again where it needs to, and writes a sourced brief. A coordinator agent then merges the four briefs into one package recommendation. Every search and result streams to the React UI over server-sent events, and the follow-up chat assistant can run fresh searches too.

## How the harness works

`AgentHarness` runs a bounded Bedrock `Converse` tool-use loop:

1. The model receives its role, the trip task, and the `web_search` tool definition.
2. When the model asks for `web_search`, the harness calls `TravelResearchProvider` (Tavily) and returns the titles, URLs, retrieval times, and excerpts. These are marked as untrusted data, not instructions.
3. The loop ends when the model stops calling tools, or when the limits are reached. Turns are capped by `AGENT_MAX_TURNS` (default 8). Searches are capped by `AGENT_MAX_SEARCHES` per specialist (default 4) and `AGENT_CHAT_MAX_SEARCHES` per chat question (default 2). Once the search budget runs out, the model is told to answer with the evidence it already has.
4. If a search fails, the error goes back to the model so it can try another query or answer with what it has. If Bedrock fails, that agent is marked failed and the trip completes as `partial`.

Each run logs token usage (input/output) and the number of searches. It does not log any trip content.

## Running locally

Requirements: Java 21+, Maven 3.9+, Node.js 22+, npm, a Tavily API key, and AWS credentials with Bedrock access to the configured model in `AWS_REGION`.

There is no demo or mock mode. The backend will not start without `TAVILY_API_KEY`, and agent runs fail without working AWS credentials. Authenticate through the AWS SDK's normal credential chain, such as `aws sso login` with a profile locally or an IAM role in AWS. Never put credentials in source, frontend variables, or committed files.

```powershell
$env:TAVILY_API_KEY = "<from your secret manager>"
$env:AWS_PROFILE = "<profile with bedrock:InvokeModel>"
$env:AWS_REGION = "ca-central-1"
mvn spring-boot:run
```

```powershell
npm install
npm run dev
```

Open the Vite URL, normally `http://localhost:5173`. The UI reaches the backend through Vite's `/api` proxy.

Optional settings: `BEDROCK_MODEL_ID`, `BEDROCK_GUARDRAIL_ID` together with `BEDROCK_GUARDRAIL_VERSION`, `AGENT_MAX_TURNS`, `AGENT_MAX_SEARCHES`, `AGENT_CHAT_MAX_SEARCHES`, and `AGENT_MAX_TOKENS`. Each specialist run makes up to `max-searches + 1` Bedrock calls plus Tavily searches, so lower these to control cost. Travel listings are research leads, not guaranteed booking inventory or confirmed prices.

## AWS Bedrock

The service uses the AWS SDK for Java v2 Bedrock Runtime `Converse` API. The default is Amazon Nova Lite through the Canada geographic inference profile `ca.amazon.nova-lite-v1:0` in `ca-central-1`. Calls routed by this profile stay in Canada (`ca-central-1` or `ca-west-1`). Set `BEDROCK_MODEL_ID` to another available model or inference profile that fits the account's cost, latency, and data-residency requirements. Model access, regions, quotas, and pricing are controlled by AWS and vary by account. The application does not silently switch models. Grant the workload identity only the Bedrock invoke permissions needed for the selected model or inference profile. Prefer EKS Pod Identity or IRSA over static AWS keys. Current profile and lifecycle details are listed in the [AWS Bedrock model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-5-5.html).

Live web research uses Tavily through the `web_search` agent tool. Weather, event, hotel, flight, and rental-car findings depend on what the configured web-search provider returns. For guaranteed forecasts or bookable rates, add contracted domain-specific APIs as more harness tools or behind `TravelResearchProvider`; no booking or purchase action is implemented.

## Container

```powershell
docker build -t vamsdest:0.1.0 .
docker run --rm -p 8080:8080 -e TAVILY_API_KEY -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN -e VITE_AUTH_REQUIRED=false vamsdest:0.1.0
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

The tests do not need credentials. `AgentHarnessTest` drives the tool-use loop with a scripted fake Bedrock client to cover search execution, budget limits, provider failures, and turn limits. The Spring context tests mock Bedrock and search.

The current run registry is in-memory and retains trip data for up to one hour. The deployment intentionally uses one replica: reconnect/replay works within one process, but restarts lose active runs, and multiple replicas cannot share trip events. Before enabling HA or autoscaling, move run state/events to shared durable storage and use a shared event broker/job queue. Production rollout also requires validating OIDC, AWS IAM, WAF, TLS, quotas, provider contracts, and operations in the target account.