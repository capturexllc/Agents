"""Builds docs/VamsDest-AWS-Architecture.docx from the diagrams in docs/diagrams/.

Run from the repository root after build_diagrams.py:
    python docs/build_architecture_docx.py
Requires python-docx.
"""

from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

DOCS = Path(__file__).resolve().parent
DIAGRAMS = DOCS / "diagrams"
OUT = DOCS / "VamsDest-AWS-Architecture.docx"

HEADER_FILL = "1F3B57"
ZEBRA_FILL = "F2F6FA"


def shade(cell, hex_fill):
    properties = cell._tc.get_or_add_tcPr()
    shading = OxmlElement("w:shd")
    shading.set(qn("w:val"), "clear")
    shading.set(qn("w:color"), "auto")
    shading.set(qn("w:fill"), hex_fill)
    properties.append(shading)


def table(doc, headers, rows, widths=None):
    grid = doc.add_table(rows=1, cols=len(headers))
    grid.style = "Table Grid"
    grid.alignment = WD_TABLE_ALIGNMENT.CENTER
    head = grid.rows[0].cells
    for index, text in enumerate(headers):
        head[index].text = ""
        run = head[index].paragraphs[0].add_run(text)
        run.bold = True
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
        shade(head[index], HEADER_FILL)
    for row_index, row in enumerate(rows):
        cells = grid.add_row().cells
        for index, text in enumerate(row):
            cells[index].text = ""
            run = cells[index].paragraphs[0].add_run(text)
            run.font.size = Pt(8.5)
            if row_index % 2 == 1:
                shade(cells[index], ZEBRA_FILL)
    if widths:
        for row in grid.rows:
            for index, width in enumerate(widths):
                row.cells[index].width = Inches(width)
    doc.add_paragraph()
    return grid


def figure(doc, filename, caption, width=6.6):
    doc.add_picture(str(DIAGRAMS / filename), width=Inches(width))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    note = doc.add_paragraph()
    note.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = note.add_run(caption)
    run.italic = True
    run.font.size = Pt(8.5)


def para(doc, text, bold_lead=None):
    p = doc.add_paragraph()
    if bold_lead:
        lead = p.add_run(bold_lead + " ")
        lead.bold = True
    p.add_run(text)
    return p


def bullets(doc, items):
    for item in items:
        doc.add_paragraph(item, style="List Bullet")


def build():
    doc = Document()
    base = doc.styles["Normal"]
    base.font.name = "Calibri"
    base.font.size = Pt(10.5)
    for section in doc.sections:
        section.left_margin = section.right_margin = Inches(0.9)
        section.top_margin = section.bottom_margin = Inches(0.8)

    title = doc.add_heading("VamsDest on AWS: Architecture", level=0)
    title.alignment = WD_ALIGN_PARAGRAPH.LEFT
    para(doc, "Serverless deployment of the multi-agent travel planner: design, components, security, "
              "delivery pipeline, and operations.")

    table(doc, ["Item", "Value"], [
        ["Document", "VamsDest AWS architecture, version 1.0 (draft for review)"],
        ["Date", "2026-10-07"],
        ["Source of truth", "infra/template.yaml, src/main/java/com/vamsdest/, src/*.ts(x), "
                            ".github/workflows/deploy.yml, docs/AWS-DEPLOYMENT.md"],
        ["Audience", "Engineering, security review, operations, and anyone approving a production launch"],
        ["Status of verification", "Template lints clean; backend and frontend build and test; Lambda package "
                                   "builds. Not yet deployed to a live AWS account (see section 13)."],
    ], widths=[1.7, 4.9])

    doc.add_heading("1. Purpose and scope", level=1)
    para(doc, "VamsDest turns a destination and dates into a sourced travel package. Four specialist agents "
              "(destination, stay and activities, flights, and taxi and car) each run a bounded Claude tool loop "
              "over live web search. A coordinator merges their briefs, and a chat assistant answers follow-up "
              "questions from the same evidence.")
    para(doc, "This document describes the AWS target architecture: what runs where, how requests and "
              "research flow, how data is stored and protected, how changes are delivered, and what remains open. "
              "It does not describe the local development setup, except where the two differ.")
    para(doc, "Out of scope: booking, purchase, or modification of any reservation. The system never performs "
              "these actions.", bold_lead="Out of scope.")

    doc.add_heading("2. Requirements that shape the design", level=1)
    table(doc, ["ID", "Requirement", "Design response"], [
        ["R1", "Run agents that take minutes, without holding a browser connection open",
         "Work is queued (SQS) and executed by a worker Lambda. The browser polls an ordered event log."],
        ["R2", "Serve many concurrent users with no servers to manage",
         "Lambda, HTTP API, SQS, DynamoDB on-demand, CloudFront. Worker concurrency is capped to bound spend."],
        ["R3", "Users see only their own trips", "JWT authorizer on every route; ownerId checked on every read; "
                                                  "other users receive 404."],
        ["R4", "No credentials in code, images, or the browser", "Tavily key in Secrets Manager; Bedrock by IAM role; "
                                                                  "browser holds only a public ID token."],
        ["R5", "Agent answers grounded in sources and honest about gaps",
         "Search results are wrapped as untrusted data; briefs cite URLs; states are completed, partial, or failed."],
        ["R6", "Repeatable, reviewed delivery", "GitHub Actions with OIDC; infrastructure as SAM; prod behind "
                                                 "environment approval; smoke tests after deploy."],
        ["R7", "Observable and recoverable", "Structured logs with token counts; alarms for DLQ, worker errors, "
                                              "and API 5xx; DLQ redrive."],
        ["R8", "Retain no more data than needed", "Event and run TTL (default 24 h); 30-day log retention; "
                                                   "no trip text in logs."],
    ], widths=[0.45, 2.6, 3.55])

    doc.add_heading("3. Key decisions", level=1)
    table(doc, ["Decision", "Why", "Rejected alternative and reason"], [
        ["Lambda for API and worker", "Requested; pay-per-use; no cluster to patch",
         "ECS/EKS: viable, but the request was for Lambda and the workload is bursty"],
        ["Queue plus worker instead of inline work", "Research takes minutes; API Gateway caps synchronous "
                                                     "requests at 30 s; Lambda caps at 15 min",
         "Synchronous Lambda per request: would time out and could not stream progress"],
        ["Polling an event log instead of SSE", "Lambda and API Gateway do not hold a long stream; the log "
                                                "gives ordering and resume for free",
         "WebSockets via API Gateway: more moving parts for the same ordering guarantee"],
        ["DynamoDB for runs and events", "Shared state across Lambda instances; TTL; atomic counter for order",
         "In-memory store (the local code): not shared across instances"],
        ["Cognito Hosted UI with PKCE", "Managed sign-in with no client secret in the browser; JWT authorizer "
                                        "native to HTTP API", "Session cookies and Spring OIDC: need a server "
                                                              "session that Lambda does not provide"],
        ["Single domain through CloudFront", "No CORS; one certificate; security headers in one place",
         "Separate API domain: needs CORS and a second certificate"],
        ["Shade one standalone jar for both functions", "One artifact, one handler per function",
         "Separate jars per function: more build targets for little gain now"],
    ], widths=[1.7, 2.6, 2.3])

    doc.add_heading("4. High-level architecture", level=1)
    para(doc, "Figure 1 shows the runtime components and their main interactions. The browser reaches a single "
              "domain. CloudFront routes static content to a private S3 bucket and API calls to the HTTP API. "
              "Everything behind the HTTP API is either a Lambda function or a managed data service.")
    figure(doc, "fig1-system-context.png", "Figure 1. System context (high level).")

    table(doc, ["Component", "Responsibility", "Key settings"], [
        ["Route 53 and ACM", "DNS and TLS for the app hostname", "Certificate in us-east-1; A and AAAA alias records"],
        ["CloudFront", "HTTPS edge; origin routing; security headers; optional WAF",
         "PriceClass_100; TLS 1.2+; /api/* uncached and forwards Authorization"],
        ["S3 web bucket", "Hosts the built React app", "Public access blocked; OAC only; versioning on; AES-256"],
        ["Cognito user pool", "Accounts and sign-in", "Email sign-in; admin-created users only; 12+ character "
                                                      "passwords; app client without a secret"],
        ["HTTP API", "Routes and JWT validation", "Default authorizer on all routes; throttle 50 rps, burst 100"],
        ["API Lambda", "Validates requests; writes runs; queues jobs; reads events",
         "Java 21; 512 MB; 10 s; no outbound internet"],
        ["SQS jobs queue", "Buffers research and chat jobs", "Visibility 960 s; maxReceiveCount 3; SSE-SQS"],
        ["Dead-letter queue", "Holds jobs that failed three times", "14-day retention"],
        ["Worker Lambda", "Runs the agentic harness and coordinator",
         "Java 21; 1024 MB; 900 s; reserved concurrency = WorkerConcurrency; batch size 1"],
        ["DynamoDB runs table", "Run records and ordered events", "On-demand; PITR; SSE; TTL on expiresAt; "
                                                                  "deletion protection in prod"],
        ["Secrets Manager", "Tavily API key", "Created outside the stack; read by worker role only"],
        ["Amazon Bedrock", "Claude via Converse", "Inference profile; optional Guardrail"],
        ["Tavily (external)", "Web search for the agents", "5 results per query; 15 s timeout"],
        ["CloudWatch, SNS", "Logs, metrics, alarms, email alerts", "30-day log retention; three alarms"],
    ], widths=[1.5, 2.6, 2.5])

    doc.add_heading("5. Delivery pipeline", level=1)
    para(doc, "Figure 2 shows how a change reaches AWS. Verification runs on every push and pull request. "
              "Deployment runs on main for dev and on manual request for dev or prod. The production environment "
              "requires a reviewer. AWS access is granted per run through OIDC, so the repository holds no AWS keys.")
    figure(doc, "fig2-delivery-pipeline.png", "Figure 2. Delivery pipeline (high level).")
    table(doc, ["Stage", "Action", "Gate"], [
        ["verify", "mvn -B verify; npm ci and npm run build; cfn-lint on infra/template.yaml",
         "Any failure stops the pipeline"],
        ["package", "scripts/package-lambda.sh builds the shaded jar and unpacks build/lambda",
         "Both handler classes must exist"],
        ["deploy", "sam deploy with per-environment parameters; prod waits for reviewer approval",
         "CloudFormation rollback on failure"],
        ["publish", "scripts/deploy-web.sh builds with Cognito outputs; uploads assets; invalidates index.html",
         "Build must succeed"],
        ["smoke test", "App returns 200; anonymous POST /api/trips returns 401", "Both checks must pass"],
    ], widths=[1.0, 4.0, 1.6])

    doc.add_heading("6. Low-level design", level=1)

    doc.add_heading("6.1 Sign-in, create a trip, and poll progress", level=2)
    para(doc, "The synchronous path is short. The API validates the request, writes a planning run, queues a job, "
              "and returns. Everything after that is asynchronous and visible through the event log.")
    figure(doc, "fig3-sign-in-and-create-trip.png", "Figure 3. Sign-in, create a trip, and poll progress.")
    table(doc, ["Method and path", "Auth", "Behaviour", "Success response"], [
        ["POST /api/trips", "JWT", "Validates TripRequest; return date must follow departure; writes run; queues trip job",
         "202 {tripId, eventsUrl}"],
        ["GET /api/trips/{tripId}/events?after=n", "JWT", "Owner check; returns events with seq > n in order",
         "200 {status, nextAfter, events[]}"],
        ["POST /api/trips/{tripId}/chat", "JWT", "Validates message (max 600 chars); queues chat job",
         "202 {requestId}; answer arrives as chat_answer"],
    ], widths=[2.1, 0.6, 2.8, 1.3])
    para(doc, "Errors use one shape, {message}. Validation errors return 400, unknown or foreign trips return 404, "
              "a missing or invalid token returns 401 from the authorizer, and unexpected faults return 500 with a "
              "generic message.")

    doc.add_heading("6.2 Research run inside the worker", level=2)
    para(doc, "The worker processes one job per invocation. It re-reads the run and skips it if a previous delivery "
              "already finished. Four specialist agents run in parallel on a four-thread pool. Each writes its "
              "progress as events. The coordinator then makes one call without tools.")
    figure(doc, "fig4-research-run-sequence.png", "Figure 4. Research run inside the worker.")

    doc.add_heading("6.3 The agentic harness", level=2)
    para(doc, "The harness is a bounded tool-use loop on Bedrock's Converse API. Claude decides whether to search, "
              "what to search for, and when it has enough evidence. The code enforces the limits: turns, searches, "
              "query length, and tokens. Search results are returned to the model as untrusted data, and a failed "
              "search becomes a tool error that the model can work around.")
    figure(doc, "fig5-agentic-harness-loop.png", "Figure 5. Agentic harness loop.", width=5.6)
    table(doc, ["Limit", "Default", "Environment variable", "Effect when reached"], [
        ["Turns per agent", "8", "AGENT_MAX_TURNS", "Agent fails; other agents continue"],
        ["Searches per specialist", "4", "AGENT_MAX_SEARCHES", "Model told to answer with current evidence"],
        ["Searches per chat question", "2", "AGENT_CHAT_MAX_SEARCHES", "Same as above, for chat"],
        ["Output tokens per call", "1500", "AGENT_MAX_TOKENS", "Model stops at the token limit"],
        ["Query length", "300 characters", "(code constant)", "Query truncated"],
        ["Results per search", "5", "(code constant)", "Fewer results returned"],
        ["Bedrock call timeout", "90 s", "(code constant)", "Call fails; agent fails"],
        ["Lambda run time", "900 s", "(template)", "Lambda stops the run; message reappears (see 6.5)"],
    ], widths=[1.8, 1.0, 1.9, 2.0])
    para(doc, "Each run logs the number of turns, searches, and input and output tokens. Logs do not contain trip "
              "text, excerpts, or the Tavily key.")

    doc.add_heading("6.4 Data model", level=2)
    para(doc, "A single table holds both run records and their events. Partition key pk groups everything for one run. "
              "Sort key sk separates the run record (META) from events (EVT# plus a zero-padded sequence). The sequence "
              "is assigned by an atomic increment on META, so parallel agents never produce the same number.")
    figure(doc, "fig6-data-model.png", "Figure 6. DynamoDB run store layout.")
    table(doc, ["Attribute", "On", "Type", "Purpose"], [
        ["pk, sk", "all items", "S", "Keys; partition by run"],
        ["ownerId", "META", "S", "Owner from JWT sub; checked on every read"],
        ["request", "META", "S (JSON)", "Trip request as submitted (validated)"],
        ["status", "META", "S", "planning, completed, or partial"],
        ["eventCount", "META", "N", "Atomic counter; last issued sequence number"],
        ["data", "EVT#", "S (JSON)", "One TripEvent (seq, type, agent, state, message, sources, correlationId)"],
        ["expiresAt", "all items", "N", "TTL in epoch seconds; default run retention 24 h"],
    ], widths=[1.2, 0.9, 1.0, 3.5])

    doc.add_heading("6.5 Failure handling and retries", level=2)
    para(doc, "Failures are split into two groups. Expected failures, such as a specialist that cannot search or a "
              "coordinator that cannot write its summary, become visible events and leave the run partial. Only "
              "infrastructure faults are rethrown, so SQS can retry them. After three receives, a message moves to "
              "the dead-letter queue and an alarm fires.")
    figure(doc, "fig7-failure-handling.png", "Figure 7. Failure handling and retry paths.")
    table(doc, ["Failure", "Detected by", "Outcome for the user", "Recovery"], [
        ["Tavily error or timeout", "Search wrapper", "Model receives an error result and continues; agent may be partial",
         "Automatic in the loop"],
        ["Bedrock throttling or timeout", "Harness call", "Agent fails; event shows the failure text",
         "SDK retry within limits; otherwise manual rerun"],
        ["Specialist exception", "Coordinator task", "agent_result state = failed; run partial",
         "User starts a new trip"],
        ["Coordinator failure", "Coordinator", "Canned summary; run partial; briefs still shown", "Same"],
        ["Worker out of time (900 s)", "Lambda", "Run stays planning until the message reappears; events may repeat",
         "Automatic retry; keep search limits modest"],
        ["Repeated infrastructure faults", "SQS receive count", "Message moves to DLQ; alarm", "Fix; redrive DLQ"],
        ["Duplicate delivery", "Status check", "Finished runs are not researched again", "None needed"],
    ], widths=[1.7, 1.3, 2.3, 1.3])

    doc.add_heading("7. Security architecture", level=1)
    para(doc, "Controls are layered: the edge filters and encrypts, the authorizer verifies identity before any code "
              "runs, each function has its own role, and data is scoped to its owner. Figure 1 shows the trust "
              "boundaries; the tables below list the controls and the permissions per role.")
    table(doc, ["Control", "Implementation"], [
        ["Transport", "HTTPS only (redirect at CloudFront; https-only to the API origin); TLS 1.2 minimum"],
        ["Headers", "HSTS (1 year, subdomains); CSP limited to self, Cognito, Google Fonts, and Unsplash; frame-ancestors none; "
                    "nosniff; strict referrer policy"],
        ["Authentication", "Cognito PKCE; ID token audience = app client; 60-minute tokens; no client secret in the browser"],
        ["Authorization", "JWT authorizer on all routes; ownerId equality on every trip read and write path"],
        ["Input validation", "Bean validation on TripRequest and ChatRequest; date order check; JSON size bounded by API Gateway"],
        ["Rate limits", "HTTP API throttle 50 rps / burst 100; optional WAF rate rule (2000 requests per 5 min per IP)"],
        ["Secrets", "Tavily key only in Secrets Manager; not in code, images, logs, or GitHub"],
        ["Prompt injection", "Search content labelled untrusted; system prompt forbids following instructions in sources; "
                             "optional Bedrock Guardrail"],
        ["Encryption", "S3 AES-256; DynamoDB SSE; SQS SSE-SQS; CloudWatch logs encrypted by default"],
        ["Audit", "CloudTrail for deploy-role actions; API and function logs; X-Ray tracing enabled on both functions"],
        ["Personal data", "Trip destination, dates, and budget; retained for RunRetentionHours; no personal data in logs"],
    ], widths=[1.4, 5.2])
    table(doc, ["Role", "Trusted by", "Permissions (scope)"], [
        ["API function role", "lambda.amazonaws.com",
         "DynamoDB CRUD on the runs table; SQS send to the jobs queue; logs; X-Ray"],
        ["Worker function role", "lambda.amazonaws.com",
         "DynamoDB CRUD on the runs table; secretsmanager:GetSecretValue on the Tavily secret; bedrock:InvokeModel "
         "on the configured inference profile and anthropic foundation models; bedrock:ApplyGuardrail; SQS receive "
         "from the jobs queue; logs; X-Ray"],
        ["Deploy role (GitHub)", "GitHub OIDC; bound to repo and environment name",
         "CloudFormation, the resources in the stack, IAM role management scoped to vamsdest-*, S3 sync to the web "
         "bucket, CloudFront invalidation"],
        ["CloudFront to S3", "cloudfront.amazonaws.com with SourceArn = this distribution", "s3:GetObject on the web bucket"],
    ], widths=[1.6, 2.2, 2.8])
    para(doc, "Known gap: the Bedrock permission uses wildcards for the foundation-model ARNs because an inference "
              "profile can route across regions. Tighten to the exact model ARNs once the regions in use are known.",
         bold_lead="Least-privilege follow-up.")

    doc.add_heading("8. Edge and routing", level=1)
    table(doc, ["Path", "Origin", "Cache", "Notes"], [
        ["/* (default)", "S3 web bucket (OAC)", "Managed CachingOptimized",
         "index.html is re-uploaded with no-cache; hashed assets immutable for a year"],
        ["/api/*", "HTTP API", "Managed CachingDisabled",
         "All methods; Authorization header forwarded (AllViewerExceptHostHeader)"],
    ], widths=[1.2, 1.9, 1.7, 1.8])
    para(doc, "No custom error pages are configured. A 403 or 404 from the origin reaches the browser unchanged, so "
              "the API's own error bodies are not replaced by the app shell.")

    doc.add_heading("9. Configuration reference", level=1)
    table(doc, ["Variable", "Function", "Source", "Default or example"], [
        ["TABLE_NAME", "API, worker", "Stack: RunTable", "vamsdest-<env>-runs"],
        ["JOB_QUEUE_URL", "API", "Stack: JobQueue", "SQS URL"],
        ["RUN_TTL_HOURS", "API, worker", "Parameter RunRetentionHours", "24"],
        ["TAVILY_SECRET_ARN", "Worker", "Parameter TavilySecretArn", "Secrets Manager ARN"],
        ["BEDROCK_MODEL_ID", "Worker", "Parameter BedrockModelId", "us.anthropic.claude-sonnet-5-5"],
        ["BEDROCK_GUARDRAIL_ID / _VERSION", "Worker", "Parameters", "Both set or both empty"],
        ["AGENT_MAX_TURNS", "Worker", "Code default", "8"],
        ["AGENT_MAX_SEARCHES", "Worker", "Parameter AgentMaxSearches", "4"],
        ["AGENT_CHAT_MAX_SEARCHES", "Worker", "Parameter AgentChatMaxSearches", "2"],
        ["AGENT_MAX_TOKENS", "Worker", "Code default", "1500"],
        ["AWS_REGION", "API, worker", "Set by Lambda", "Deployment region"],
        ["JAVA_TOOL_OPTIONS", "API", "Template", "Tiered compilation level 1 for faster starts"],
    ], widths=[2.0, 1.1, 2.0, 1.5])

    doc.add_heading("10. Scalability, performance, and cost", level=1)
    bullets(doc, [
        "Concurrency is the worker's reserved concurrency (WorkerConcurrency, default 5). Extra trips wait in SQS; "
        "this also caps Bedrock and Tavily spend.",
        "Latency: API calls return in well under a second. Trip completion takes minutes and depends mainly on "
        "Bedrock output speed and the number of searches.",
        "Cold starts: the packaged jar is large (about 48 MB compressed). First requests after idle periods are "
        "slower. SnapStart on the API alias is the next step.",
        "Per trip, the spend is roughly four specialist conversations plus one coordinator call, each with up to "
        "eight turns, and up to 16 Tavily searches. Budget with the limits in section 6.3.",
        "Storage and logs are small. DynamoDB on-demand scales with event writes, which is about a few dozen per trip.",
    ])

    doc.add_heading("11. Operations and observability", level=1)
    table(doc, ["Signal", "Source", "Alarm", "Action"], [
        ["Dead-letter messages", "SQS ApproximateNumberOfMessagesVisible on DLQ", "> 0 over 5 min",
         "Read the message; fix; redrive"],
        ["Worker errors", "Lambda Errors on worker", ">= 3 over 5 min", "Read worker logs for the stack trace"],
        ["API server errors", "API Gateway 5xx", ">= 5 over 5 min", "Check API logs and recent deploy"],
    ], widths=[1.5, 2.3, 1.3, 1.5])
    bullets(doc, [
        "Logs: /aws/lambda/<app>-<env>-api and -worker, retained 30 days.",
        "Traces: X-Ray active on both functions.",
        "Business signal: the worker logs turns, searches, and token counts per agent; use these for cost review.",
        "Rollback: redeploy the previous commit. Web-only rollback: rerun the publish step from the previous build.",
    ])

    doc.add_heading("12. Risks, limitations, and open items", level=1)
    table(doc, ["Item", "Risk", "Mitigation or next step"], [
        ["Research accuracy", "Prices, availability, and weather come from web search and can be stale or wrong",
         "Labelled as leads in the UI; add provider contracts for bookable data (outside this scope)"],
        ["Worker time limit", "A slow run can exceed 900 s and repeat events on retry",
         "Add an in-harness deadline; keep search limits modest"],
        ["Session length", "One-hour ID token without refresh; users sign in again",
         "Add refresh-token handling in the web app"],
        ["Cold starts", "Large jar slows the first request after idle",
         "Trim dependencies for the API function; enable SnapStart"],
        ["Single region", "No cross-region failover", "Accept for now; document recovery by redeploy"],
        ["Bedrock IAM scope", "Wildcard foundation-model ARNs", "Tighten after regions are confirmed"],
        ["Legacy artifacts", "EKS manifests and the Docker image describe the earlier single-process design; the "
                             "Docker image runs without authentication", "Do not expose the container; retire these "
                                                                         "or update them"],
        ["Secret rotation", "Manual; worker reads the key at cold start", "Automate with a rotation Lambda if needed"],
        ["Deletion protection", "Retain policies keep data on stack deletion", "Operators delete retained resources deliberately"],
    ], widths=[1.4, 2.6, 2.6])

    doc.add_heading("13. Verification status", level=1)
    table(doc, ["Check", "Result"], [
        ["Backend tests (mvn -B verify)", "23 tests pass: Spring context and API, harness loop, coordinator (full run "
                                          "and partial), service and handler routes, DynamoDB sequence logic"],
        ["Frontend type-check and build", "Passes (tsc -b, vite build)"],
        ["CloudFormation / SAM lint (cfn-lint)", "Passes with no findings"],
        ["Lambda package", "Shaded jar contains com/vamsdest/lambda handlers at its root and no BOOT-INF entries"],
        ["Live deployment to AWS", "Not performed from this repository. Follow the first-deployment checklist in "
                                   "docs/AWS-DEPLOYMENT.md, section 9."],
        ["Live Bedrock and Tavily calls", "Not performed; the harness is tested with a scripted Bedrock client"],
    ], widths=[2.2, 4.4])

    doc.add_heading("Appendix A. Source map", level=1)
    table(doc, ["Concern", "File"], [
        ["Infrastructure", "infra/template.yaml, infra/samconfig.toml"],
        ["Lambda entry points", "src/main/java/com/vamsdest/lambda/ApiHandler.java, WorkerHandler.java, LambdaWiring.java"],
        ["Service rules", "src/main/java/com/vamsdest/service/TripService.java"],
        ["Harness and coordinator", "src/main/java/com/vamsdest/agent/AgentHarness.java, TripCoordinator.java, JobProcessor.java"],
        ["Storage", "src/main/java/com/vamsdest/agent/RunStore.java, DynamoRunStore.java, InMemoryRunStore.java"],
        ["Web sign-in and API", "src/auth.ts, src/api.ts, src/App.tsx"],
        ["Delivery", ".github/workflows/deploy.yml, scripts/package-lambda.sh, scripts/deploy-web.sh"],
        ["Operations guide", "docs/AWS-DEPLOYMENT.md"],
    ], widths=[1.9, 4.7])

    doc.save(OUT)
    print("wrote", OUT.name)


if __name__ == "__main__":
    build()
