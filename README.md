# Dreamhaven

Dreamhaven, by Vasolai, is a private destination planner. A traveler enters a destination and dates, and four specialist agents research it in parallel: Destination, Stay & Activities, Flights & Transport, and Taxi & Car. A coordinator merges their briefs into one package. A follow-up chat can answer questions about the trip, and it can search again when the findings don't cover a question.

The agents are built on **LangGraph4j** (graph orchestration) and **LangChain4j** (the Bedrock chat model and tool specifications). They run in AWS Lambda, behind API Gateway and CloudFront, with a React frontend.

## Architecture at a glance

```
Browser ──► CloudFront (+WAF) ──► /         S3 web bucket
                            └──► /api/*  ──► API Gateway ──► API Lambda ──► Trip runs (DynamoDB)
                                                                  │
                                                                  └──► Jobs queue (SQS) ──► Agent worker Lambda
                                                                                              │
                 ┌────────────────────────────────────────────────────────────────────────────┤
                 │  LangGraph4j trip graph:  research (4 ReAct agents, parallel) ──► coordinate
                 │  ReAct agent:  model node ⇄ tools node  (budgets enforced in code)
                 │  Tools:  web_search (Tavily)  ·  search_policies (RAG over policy corpus)
                 │  Calls:  Bedrock Converse via LangChain4j  ·  Resilience4j circuit breakers
                 └──► Trip runs (events) ──► polled by the browser
Prompt & policy bucket (S3): prompts, runtime.yml, policy PDFs/Excel, embedding model
  ├──► worker (prompts, settings)
  └──► ingest Lambda (policy documents) ──► Policy chunks (DynamoDB, vectors)
```

| Layer | What it does | Where |
|---|---|---|
| Edge | HTTPS, optional WAF IP allowlist, static web app, `/api/*` routing | CloudFront, AWS WAF, S3 |
| API | Creates trips, serves polled events, queues trip and chat jobs | API Gateway HTTP API, `ApiHandler` |
| Agent runtime | Trip graph, ReAct agents, tools, budgets, circuit breakers | `agent/` (LangGraph4j, LangChain4j) |
| Prompts and settings | Agent roles, tasks, safety rules, turn and search budgets | S3 `v1/prompts/`, `v1/config/runtime.yml` (bundled defaults in the jar) |
| RAG | Policy and rule documents, chunked and embedded for search | `rag/`, ingest Lambda, DynamoDB chunk table |
| Data | Run state and event log; queued jobs; dead letters | DynamoDB, SQS |
| Operations | Logs, alarms, alert topic | CloudWatch, SNS |

The complete AWS inventory (every component, region, configuration, and security control) is in [docs/AWS-INFRASTRUCTURE.md](docs/AWS-INFRASTRUCTURE.md), with a PDF and diagram generated from the same data by `docs/build_infra_pdf.py`.

## How the agents work

**The trip graph** (`TripGraph`, a LangGraph4j `StateGraph`) has two nodes:

1. `research` runs the four specialists in parallel. Each specialist is a ReAct agent with its own role, task, and search budget. Each one writes its briefing into the shared graph state.
2. `coordinate` reads every briefing from that state and writes one consolidated plan. If the coordinator fails, the run still completes with the specialists' briefs, and the status is `partial`.

Each specialist's progress (search started, reading results, finished or failed) is written to the run's event log as it happens.

**A ReAct agent** (`ReactAgent`, also a LangGraph4j graph) loops between two nodes:

- `model` calls the chat model with the conversation so far and the tools it's allowed to use.
- `tools` runs whatever tool the model requested and feeds the result back. The loop ends when the model answers without a tool call, or when the turn budget (`maxTurns`) runs out.

Budgets are enforced in code, not left to the prompt:

- **Turns:** `agent.maxTurns` (default 8).
- **Web searches:** `agent.specialistSearches` per specialist (default 4), `agent.chatSearches` per chat question (default 2).
- **Policy lookups:** `agent.policySearches` (default 3).

When a budget runs out, the tool returns a message telling the model to answer with what it already has.

Tool results are data, not instructions. Web results are labelled as untrusted, and policy excerpts are labelled as reference text. The shared safety rules in `prompts/system-safety.md` apply to every agent.

**Circuit breakers** (`CircuitBreakers`, Resilience4j) wrap every model call and every search. When a dependency fails most of the time, its breaker opens and calls fail immediately, so the agent answers with what it has instead of waiting on timeouts. Thresholds are in `runtime.yml` under `breakers`.

**Graph state** holds only serializable data. The conversation is stored as simple `Turn` records and converted to LangChain4j messages at the model boundary. Listeners and run contexts are kept in a process registry, and the state holds only their keys. LangGraph4j copies state between steps, so anything placed in state must serialize.

### LangSmith

LangSmith is not enabled. It has no official Java SDK, and tracing would send prompts and outputs outside AWS. Run events are written to the run store, and logs go to CloudWatch.

## Prompts and runtime settings

Every prompt is a template in `src/main/resources/prompts/`, with `{{placeholders}}` filled at run time:

| Files | Purpose |
|---|---|
| `system-safety.md` | Safety rules shared by every agent |
| `search-budget.md`, `tool-web-search.md`, `tool-policy-search.md` | Tool budgets and tool descriptions |
| `role-*.md` | Each agent's role: destination, stay, transport, taxi, coordinator, chat |
| `task-*.md` | Each agent's task for the trip (dates, destination, budget) |
| `runtime.yml` | Model id, token and turn limits, search budgets, circuit breaker thresholds |

At run time the worker reads these from the prompt bucket (`PROMPTS_BUCKET`, prefix `v1/`) and caches them for `PROMPTS_CACHE_SECONDS` (default 300). If a file is missing from S3, the copy bundled in the jar is used. Upload changes with `scripts/upload-prompts.sh`. Changes to `runtime.yml` take effect within the cache window, except circuit breaker thresholds, which are read at cold start.

## RAG: policy and rule documents

The agents can search an internal policy corpus with the `search_policies` tool.

**Ingest.** Upload a PDF or Excel file to `v1/rag/` in the prompt bucket. (`scripts/upload-policies.sh` uploads everything in `docs/rag/`.) The upload triggers the ingest Lambda, which:

1. Extracts the text. PDFs are read page by page with PDFBox. Excel workbooks are read row by row with POI, and each row becomes `Header: value; Header: value`, so one rule stays in one chunk.
2. Splits the text into chunks of up to 1000 characters with 150 characters of overlap, breaking at word boundaries.
3. Embeds each chunk with **all-MiniLM-L6-v2** (384 dimensions), an open-source model that runs inside the function through ONNX Runtime. It needs no API call and no account quota.
4. Replaces the document's earlier chunks in the `rag-chunks` DynamoDB table. Each item stores the text, source, location, and vector.

Re-uploading a file replaces its chunks. The delete and write steps are not atomic, so a failure partway through can leave a partial set until the file is uploaded again.

**Search.** The worker loads the chunk table into an in-memory index, cached for `RAG_CACHE_SECONDS` (default 300). A query is embedded the same way, and the best matches by cosine similarity are returned: up to 4 chunks, with a score of at least 0.25. Matches are cited as `policy://<file>#<location>`.

The index scans the whole table, which is fine for a small policy corpus. A larger corpus needs a real vector index.

**Files.** `docs/rag/` contains a sample travel-policy PDF, an Excel template (`policy-data-template.xlsx`) to fill in, and a readme on the formats.

## Repository layout

```
src/main/java/com/vamsdest/
  agent/      TripGraph, ReactAgent, TripCoordinator, CircuitBreakers, PromptCatalog,
              RuntimeSettings, ModelFactory, RunRegistry, JobProcessor, run store, providers
  rag/        DocumentReader, Chunker, LocalEmbedder, DynamoChunkStore, PolicyIndex,
              PolicyRetriever, IngestService
  lambda/     ApiHandler, WorkerHandler, IngestHandler, LambdaWiring (Lambda object graph)
  api/        Request and response records for the HTTP API
  service/    TripService (validation and orchestration of the API calls)
  config/     Spring configuration (local runs): Bedrock, agents, executor, security
src/main/resources/prompts/   Prompt templates and runtime.yml defaults
src/test/java/                Unit and integration tests (agent, rag, lambda, Spring context)
src/                          React frontend (App.tsx, api.ts, styles)
infra/template.yaml           AWS SAM template: all serverless resources
infra/samconfig.toml          Deploy configuration per environment (dev, prod)
scripts/                      Packaging, deploy, prompt/model/policy upload scripts
docs/                         Architecture, deployment guides, RAG files, generator
deploy/k8s/                   Legacy EKS manifests (not deployed by CI)
```

## Running locally

Requirements: Java 21, Maven 3.9+, Node.js 22+, and a Tavily API key for web search. AWS credentials with Bedrock access are needed for real agent runs.

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

Open the Vite URL (normally `http://localhost:5173`). The UI reaches the backend through Vite's `/api` proxy.

Local runs read prompts from the bundled copies in the jar. The policy corpus is empty locally, so `search_policies` returns nothing. Embeddings use the model bundled in the jar.

## Configuration

Environment variables (set by the SAM template for Lambda, or in your shell for local runs):

| Variable | Default | Purpose |
|---|---|---|
| `BEDROCK_MODEL_ID` | `ca.amazon.nova-lite-v1:0` | Chat model (also settable in `runtime.yml`) |
| `BEDROCK_GUARDRAIL_ID`, `BEDROCK_GUARDRAIL_VERSION` | empty | Optional Bedrock guardrail; both or neither |
| `AGENT_MAX_TURNS` | 8 | Model turns per agent run |
| `AGENT_MAX_SEARCHES` | 4 | Web searches per specialist |
| `AGENT_CHAT_MAX_SEARCHES` | 2 | Web searches per chat question |
| `AGENT_MAX_TOKENS` | 1500 | Output tokens per model call |
| `PROMPTS_BUCKET`, `PROMPTS_PREFIX` | from stack, `v1/` | Where prompts, settings, and policy files live |
| `PROMPTS_CACHE_SECONDS` | 300 | Prompt and settings cache lifetime |
| `RAG_TABLE_NAME` | from stack | Policy chunk table; unset disables `search_policies` |
| `RAG_CACHE_SECONDS` | 300 | Policy index cache lifetime |
| `TAVILY_SECRET_ARN` | from stack | Secret holding the Tavily API key (Lambda) |
| `TAVILY_API_KEY` | none | Tavily key for local runs |

Settings in `runtime.yml` (in S3 or bundled):

```yaml
model:
  maxTokens: 1500
agent:
  maxTurns: 8
  specialistSearches: 4
  chatSearches: 2
  policySearches: 3
breakers:
  failureRateThreshold: 50   # percent of failed calls that opens the breaker
  slidingWindowSize: 10
  minimumCalls: 5
  openSeconds: 30
```

## Deploying to AWS

The stack is defined in `infra/template.yaml` (AWS SAM). The GitHub Actions workflow `.github/workflows/deploy.yml` runs on every push to `main` (dev) or on demand (dev or prod). It:

1. Runs the Maven tests and the frontend build.
2. Runs `cfn-lint` on the template.
3. Packages the Lambda code (`scripts/package-lambda.sh`) and runs `sam deploy`.
4. Uploads the prompts (`scripts/upload-prompts.sh`), publishes the embedding model once (`scripts/fetch-embedding-model.sh`), and uploads the policy documents (`scripts/upload-policies.sh`).
5. Publishes the web app (`scripts/deploy-web.sh`) and runs a smoke test.

Authentication to AWS uses GitHub OIDC. No long-lived AWS keys are stored.

Manual deploy, from the repository root:

```bash
scripts/package-lambda.sh
cd infra && sam deploy --config-env dev --stack-name vamsdest-dev --region ca-central-1 \
  --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND \
  --parameter-overrides Environment=dev TavilySecretArn=<arn> EnableWaf=false
cd .. && scripts/upload-prompts.sh vamsdest-dev ca-central-1
scripts/fetch-embedding-model.sh vamsdest-dev ca-central-1
scripts/upload-policies.sh vamsdest-dev ca-central-1
scripts/deploy-web.sh vamsdest-dev ca-central-1
```

The Lambda package must stay under the 250 MB unzipped limit. The worker and ingest functions depend on the embedding model and ONNX Runtime; the model is kept out of the package and loaded from S3 instead. See `docs/AWS-DEPLOYMENT.md` for prerequisites (ACM certificate, DNS, Tavily secret, Bedrock model access, and the deploy role).

## Testing

```powershell
mvn test
npm run build
```

The tests need no AWS credentials or network access:

- `agent/ReactAgentTest`: the ReAct loop with a scripted model. Covers web search, the search budget, the turn limit, the circuit breaker, and the policy tool.
- `agent/TripCoordinatorTest`: the full trip graph with a scripted model, including the partial-failure path.
- `agent/ModelFactoryTest`: a real LangChain4j Bedrock call with the Bedrock client mocked. This catches SDK mismatches that only show up at run time.
- `agent/PromptCatalogTest`: prompt rendering, the bundled fallback, and settings overlay.
- `rag/`: chunking, Excel and PDF extraction, cosine ranking, and the local embedding model (checks that related sentences rank higher).
- `lambda/ApiHandlerTest`, `DynamoRunStoreTest`, and the Spring context test.

## Known limits

- **No sign-in.** Anyone who can reach the URL can create trips, and each trip spends Bedrock and Tavily budget. Enable the WAF allowlist (`EnableWaf=true` in us-east-1 with `AllowedCidrs`) or add authentication before sharing the URL publicly.
- **Policy search** scans the whole chunk table into memory. Fine for a small corpus; a larger corpus needs a vector index.
- **Re-ingest** is not atomic (see RAG above).
- **Package size.** The worker and ingest functions are about 228 MB unzipped against the 250 MB limit.
- **Bedrock embeddings** are not used. The Titan Text Embeddings V2 on-demand quota is 0 in this account and cannot be raised through Service Quotas.
- **WAF** must be in us-east-1. `samconfig.toml` deploys to ca-central-1 by default, so `EnableWaf=true` fails there until the region is aligned.
- **Results are research leads**, not guaranteed inventory or confirmed prices. No booking or purchase action is implemented.
- **Legacy EKS path.** `deploy/k8s` and `docs/DEPLOY-EKS-VASOLAI.md` describe the old Spring harness and an OIDC sign-in that no longer exists. CI does not deploy them.

## More documentation

- [docs/AWS-INFRASTRUCTURE.md](docs/AWS-INFRASTRUCTURE.md): every AWS component, with the PDF and diagram alongside it.
- [docs/AWS-DEPLOYMENT.md](docs/AWS-DEPLOYMENT.md): step-by-step deployment and troubleshooting.
- [docs/STEP-BY-STEP-AWS-SETUP.md](docs/STEP-BY-STEP-AWS-SETUP.md): first-time AWS account setup.
- [docs/rag/README.md](docs/rag/README.md): formats for policy documents.
