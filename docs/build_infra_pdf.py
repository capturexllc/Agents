"""Builds the AWS infrastructure inventory for VamsDest.

Writes three files next to this script's parent folder:
  docs/diagrams/aws-architecture.png       architecture diagram
  docs/AWS-INFRASTRUCTURE.md               component list (Markdown)
  docs/VamsDest-AWS-Infrastructure.pdf     the same content as a PDF with the diagram

Run from the repository root:  python docs/build_infra_pdf.py
Requires matplotlib and reportlab. The content below is taken from infra/template.yaml, the Lambda code and the
deploy scripts. When the template changes, change the matching row here and rerun.
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch  # noqa: E402

from reportlab.lib import colors  # noqa: E402
from reportlab.lib.pagesizes import A4  # noqa: E402
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet  # noqa: E402
from reportlab.lib.units import mm  # noqa: E402
from reportlab.platypus import (  # noqa: E402
    Image,
    KeepTogether,
    ListFlowable,
    ListItem,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

HERE = Path(__file__).resolve().parent
DIAGRAM = HERE / "diagrams" / "aws-architecture.png"
MARKDOWN = HERE / "AWS-INFRASTRUCTURE.md"
PDF = HERE / "VamsDest-AWS-Infrastructure.pdf"

TITLE = "VamsDest on AWS: infrastructure inventory"

SUMMARY = ('Everything the serverless stack (infra/template.yaml) creates, plus what it depends on and what it does '
 'not create. The stack deploys to a single AWS region. CloudFront, Route 53 and the WAF web ACL are global '
 'or us-east-1 services. The agent runtime runs inside Lambda: a LangGraph4j trip graph of ReAct agents '
 '(LangChain4j for the chat model), with circuit breakers, prompts and settings in S3, and a RAG pipeline '
 'over policy PDFs and Excel files using an open-source embedding model. The template creates no VPC, '
 'subnets, NAT gateway, load balancer or EC2 instances. There is no sign-in. Protection comes from '
 'CloudFront, AWS WAF (when enabled), API Gateway throttling and IAM.')

# ---------------------------------------------------------------------------
# Inventory. Columns: ID, component, AWS service, configuration, scope (region / AZ), security, notes
# ---------------------------------------------------------------------------
COLUMNS = ["ID", "Component", "AWS service", "Configuration", "Scope and AZ", "Security", "Notes"]

INVENTORY = [('C01',
  'DNS record for the app',
  'Route 53',
  'A and AAAA alias to CloudFront (only if AppDomainName is set)',
  'Global',
  'DNS only',
  'Uses an existing public hosted zone (HostedZoneId).'),
 ('C02',
  'TLS certificate',
  'AWS Certificate Manager',
  'Covers AppDomainName. Used by CloudFront with TLSv1.2_2021',
  'us-east-1 only',
  'TLS 1.2 minimum',
  'Created outside this stack. CloudFront requires us-east-1.'),
 ('C03',
  'Web and API edge',
  'Amazon CloudFront',
  'PriceClass_100. HTTP/2 and HTTP/3, IPv6. Default behavior serves the web app from S3. /api/* goes to API '
  'Gateway with caching off',
  'Global (edge locations)',
  'Redirect HTTP to HTTPS for web. HTTPS only to the API origin',
  'Origin Access Control to S3 (C07).'),
 ('C04',
  'Security response headers',
  'CloudFront response headers policy',
  'HSTS 1 year with subdomains, nosniff, frame DENY, strict-origin-when-cross-origin, CSP',
  'Global',
  'Browser hardening',
  "CSP connect-src allows only the app's own origin."),
 ('C05',
  'Web application firewall',
  'AWS WAF (CLOUDFRONT scope)',
  'Rule 0 block any IP not in the allowlist. Rule 1 AWS Common Rule Set. Rule 2 Known Bad Inputs. Rule 3 '
  'rate limit of 2000 requests per 5 minutes per IP. Default action allow',
  'Global. Must be created in us-east-1',
  'Enabled only when EnableWaf=true',
  'With AllowedCidrs empty, every IP is blocked.'),
 ('C06',
  'Allowlist IP sets',
  'AWS WAF IP sets',
  'IPv4 set and IPv6 set, both from template parameters',
  'Global (us-east-1)',
  'Access control at the edge',
  'Used by C05.'),
 ('C07',
  'Web app bucket',
  'Amazon S3',
  'Private. Block all public access. SSE-S3 (AES256). Versioning on. BucketOwnerEnforced. Bucket policy '
  'allows only CloudFront and denies non-TLS',
  'Regional bucket',
  'Encrypted at rest. Enforces TLS',
  'Holds the built React app: index.html (no-cache) and assets (immutable).'),
 ('C08',
  'CloudFront access to S3',
  'CloudFront Origin Access Control',
  'sigv4, always signed',
  'Global',
  'Only CloudFront can read C07',
  'No public bucket URLs.'),
 ('C09',
  'HTTP API',
  'Amazon API Gateway (HTTP API, $default stage)',
  'No authorizer. Default throttle 50 requests/s with burst 100. Routes: POST /api/trips, GET '
  '/api/trips/{tripId}/events, POST /api/trips/{tripId}/chat',
  'Regional',
  'No sign-in. Anyone who can reach the URL can create trips',
  'Requests share one owner (anonymous).'),
 ('C10',
  'API function',
  'AWS Lambda (Java 21, x86_64)',
  '512 MB. 10 s timeout. X-Ray tracing active. Handler ApiHandler',
  'Regional. AWS spreads the function across AZs',
  'No caller identity',
  'Creates runs, serves polled events, queues trip and chat jobs. Does no agent work.'),
 ('C11',
  'Agent worker',
  'AWS Lambda (Java 21, x86_64)',
  '2048 MB. 900 s timeout. X-Ray tracing. Batch size 1. Maximum concurrency set by WorkerConcurrency '
  '(default 5)',
  'Regional. AWS spreads the function across AZs',
  'Reads the Tavily key (C23), prompts (C15) and model access (C24)',
  'Runs the LangGraph4j trip graph: four ReAct agents in parallel, then a coordinator. Uses LangChain4j '
  'Bedrock chat model, Resilience4j circuit breakers, and the policy search tool.'),
 ('C12',
  'Job queue',
  'Amazon SQS (standard)',
  'Visibility timeout 960 s. Retention 4 days. SSE-SQS. Redrive to C13 after 3 receives',
  'Regional, managed across AZs',
  'SSE-SQS encryption',
  'Longer visibility than the worker timeout, so a running job is not redelivered.'),
 ('C13',
  'Dead-letter queue',
  'Amazon SQS',
  'Retention 14 days. SSE-SQS',
  'Regional',
  'SSE-SQS encryption',
  'Messages here trigger alarm C21 (A1).'),
 ('C14',
  'Trip runs table',
  'Amazon DynamoDB',
  'On-demand. Keys pk and sk. TTL on expiresAt (RUN_TTL_HOURS, default 24). Point-in-time recovery on. SSE '
  'on. Deletion protection in prod',
  'Regional, replicated across 3 AZs by DynamoDB',
  'Encrypted at rest. IAM access only',
  'Stores each run, its status, and its event log.'),
 ('C15',
  'Prompt, settings and policy bucket',
  'Amazon S3',
  'Private, versioned, SSE-S3. Prefix v1/: prompts/*.md (agent roles, tasks, tools, safety), '
  'config/runtime.yml (model id, turn and search budgets, breaker thresholds), rag/ (policy PDFs and Excel), '
  'models/ (embedding model files)',
  'Regional bucket',
  'Encrypted at rest. Public access blocked. Worker reads prompts; ingest reads rag/ and models/',
  'Worker caches prompts for PROMPTS_CACHE_SECONDS (default 300). Falls back to copies bundled in the jar if '
  'a file is missing.'),
 ('C16',
  'Ingest function',
  'AWS Lambda (Java 21, x86_64)',
  '2048 MB. 300 s timeout. X-Ray tracing. Triggered by S3 ObjectCreated on v1/rag/',
  'Regional',
  'Reads only v1/rag/ and v1/models/. Writes only C17',
  'Extracts text (PDFBox for PDF, POI for Excel), chunks it, embeds each chunk in-process, and replaces the '
  "document's chunks."),
 ('C17',
  'Policy chunk table',
  'Amazon DynamoDB',
  'On-demand. pk DOC#<document id>, sk CHUNK#<index>. Stores chunk text, source, location, and a float32 '
  'vector. Point-in-time recovery on. SSE on',
  'Regional, replicated across 3 AZs',
  'Encrypted at rest. IAM access only',
  'Read by the worker for search_policies. The worker scans it and caches the index for RAG_CACHE_SECONDS.'),
 ('C18',
  'Embedding model',
  'Open-source model (all-MiniLM-L6-v2, ONNX), stored in C15',
  '384-dimension embeddings through ONNX Runtime and DJL tokenizers. No external API',
  'Runs inside C11 and C16',
  'No data leaves the function',
  'Copied from S3 to /tmp on cold start (about 90 MB). No per-request cost and no account quota.'),
 ('C19',
  'Log groups',
  'Amazon CloudWatch Logs',
  'One group each for the API function, worker and ingest function. Retention 30 days',
  'Regional',
  'The functions log run and error events, not prompt or trip text',
  'Look here first for worker and ingest errors.'),
 ('C20',
  'Alert topic',
  'Amazon SNS (topic)',
  'Receives alarms C21. Optional email subscription from AlertEmail',
  'Regional',
  'Topic policy default',
  'Empty AlertEmail creates the topic with no subscriber.'),
 ('C21',
  'Alarms',
  'Amazon CloudWatch alarms',
  'A1 dead-letter queue has messages (more than 0 in 5 min). A2 worker errors (3 or more in 5 min). A3 API '
  '5xx (5 or more in 5 min)',
  'Regional',
  'Alarm actions go to C20',
  'Treat missing data as not breaching.'),
 ('C22',
  'Execution roles',
  'AWS IAM roles (created by SAM)',
  'One role each for C10, C11 and C16. Each policy is limited to the table, queue, bucket prefix or secret '
  'it needs',
  'Global (IAM)',
  'Deployment needs CAPABILITY_IAM',
  'Model access is granted by the Bedrock invoke statement on the worker role.'),
 ('C23',
  'Search API key',
  'AWS Secrets Manager (created outside the stack)',
  'Holds the Tavily API key. Its ARN is passed as TavilySecretArn',
  'Regional',
  'Read only by the worker role',
  'Not stored in GitHub or in the repository.'),
 ('C24',
  'Chat model',
  'Amazon Bedrock (Converse API, through LangChain4j)',
  'Model from runtime.yml (model.id), default ca.amazon.nova-lite-v1:0. Optional guardrail (ID and version)',
  'Regional (model access is enabled per account and region)',
  'Worker role allows InvokeModel and ApplyGuardrail only',
  "Circuit breaker 'bedrock' stops calls after repeated failures. Docs suggest a Claude model; confirm "
  'access.'),
 ('C25',
  'Deploy pipeline',
  'GitHub Actions with AWS OIDC role',
  'Builds and tests (Maven, npm), cfn-lint, sam deploy, then uploads prompts, the embedding model and policy '
  'documents, and publishes the web app',
  'Outside AWS runtime',
  'No long-lived AWS keys. Role from AWS_DEPLOY_ROLE_ARN',
  'Smoke test expects a 404 from a read of a nonexistent trip.')]

NOT_CREATED = [
    ("VPC, subnets, route tables", "Lambda, DynamoDB, SQS, SNS and S3 run on AWS-managed networking. No private subnets or private database."),
    ("NAT gateway, internet gateway", "Not created. The functions reach Bedrock, SNS and Tavily over public AWS endpoints, secured with IAM."),
    ("Security groups, network ACLs", "Not created. There are no VPC-attached resources to protect."),
    ("VPC endpoints, PrivateLink", "Not created. Add them if the functions are moved into a VPC."),
    ("Load balancer", "Not created. CloudFront is the entry point."),
    ("EC2 instances, Auto Scaling", "Not created. All compute is Lambda."),
    ("AWS Network Firewall, Shield Advanced", "Not created."),
    ("Customer-managed KMS keys", "Not created. Encryption uses AWS-owned or AWS-managed keys (SSE-S3, SSE-SQS, DynamoDB default)."),
    ("Availability zones", "Not chosen in the template. Lambda, DynamoDB, SQS, SNS, S3 and API Gateway are managed by AWS across AZs in the region."),
]

FLOWS = [('Load the web app',
  'Browser to CloudFront, which reads from S3 (C07) through Origin Access Control (C08). index.html is never '
  'cached.'),
 ('Plan a trip',
  'Browser to CloudFront (C03), then /api/* to API Gateway (C09). The API function (C10) writes a run to C14 '
  'and queues a job in C12. The worker (C11) runs the LangGraph4j trip graph. Four ReAct agents run in '
  'parallel: each one loops between the chat model (C24) and its tools, web_search (Tavily, C23) and '
  'search_policies (C17, with embeddings from C18). Each model and search call goes through a circuit '
  'breaker. Briefings are shared through graph state, the coordinator merges them, and each step is written '
  'to C14. The browser polls the events.'),
 ('Prompts and settings',
  'The worker reads prompt templates and runtime.yml from C15 and caches them. Edits take effect within the '
  'cache window. Circuit breaker thresholds apply after the next cold start.'),
 ('Policy document upload',
  'An operator uploads a PDF or Excel file to v1/rag/ in C15. The ingest function (C16) extracts the text, '
  "chunks it, embeds each chunk with C18, and replaces that document's chunks in C17."),
 ('Chat follow-up',
  'A follow-up question runs a single ReAct agent with the chat search budget, using the findings already '
  'stored in C14.'),
 ('Failed job',
  'SQS retries the job up to 3 times, then moves it to the dead-letter queue (C13). Alarm C21 fires and '
  'notifies the alert topic (C20).')]

SECURITY = ['There is no sign-in. The API and web app are open to anyone who can reach the CloudFront URL, unless the '
 'WAF allowlist is enabled.',
 'Web search results and policy excerpts are labelled as data, not instructions. The shared safety rules '
 '(prompts/system-safety.md) apply to every agent.',
 'Search budgets, policy-search budgets and turn limits are enforced in code, not left to the prompt.',
 "Circuit breakers stop calls to a failing model or search service, so failures don't turn into retry "
 'storms.',
 'The prompt, settings and policy bucket is private and reached only by the worker and ingest roles. '
 'Insecure transport is denied.',
 'Graph state holds only serializable data. Per-run objects (listeners, run contexts) stay in a process '
 'registry, not in state.',
 'Security headers (HSTS, CSP, frame DENY, nosniff) are set at the edge.',
 'WAF (when enabled) blocks every IP outside the allowlist, and adds AWS managed rules and a per-IP rate '
 'limit.',
 'Deploys use GitHub OIDC, not stored AWS keys. Encryption at rest is on for DynamoDB, SQS and S3.']

RISKS = ['No sign-in. Anyone with the URL can create trips, which spends Bedrock and Tavily budget. Enable the WAF '
 'allowlist (EnableWaf=true with AllowedCidrs) or add authentication before sharing the URL publicly.',
 'Lambda package size. The worker and ingest functions unzip to about 228 MB against a 250 MB limit. Adding '
 'more dependencies, or bundling the model in the jar again, will break deployment. The model is loaded from '
 'S3 for this reason.',
 'Policy retrieval scans the whole chunk table into memory and caches it. That is fine for a small policy '
 'corpus, but a larger corpus needs a real vector index.',
 'Re-ingesting a document deletes its old chunks, then writes the new ones. The two steps are not atomic, so '
 'a failure in the middle can leave a partial set until the next upload.',
 'Bedrock embeddings were not usable: the on-demand Titan Text Embeddings V2 quota is 0 and not adjustable '
 "through Service Quotas. Embeddings now run in-process, so this doesn't block RAG.",
 'WAF location. The WAF web ACL uses CLOUDFRONT scope and must be created in us-east-1. samconfig.toml '
 'deploys to ca-central-1 by default, so EnableWaf=true will fail there.',
 'CFN lint. cfn-lint reports errors on the WAF IPSetReferenceStatement properties (ARN and Arn spelling). CI '
 'runs cfn-lint, so the deploy job will not pass until this is resolved.',
 'Runtime settings. Model id, turn budgets and search budgets reload within the prompt cache window. Circuit '
 'breaker thresholds are read at cold start.',
 'The legacy EKS path. deploy/k8s and docs/DEPLOY-EKS-VASOLAI.md still describe the old Spring harness and '
 'an OIDC sign-in that no longer exists. CI does not deploy this path.',
 'The chat model. The default is Amazon Nova Lite, and some guides suggest a Claude model. Confirm the model '
 'id and access in the account before the first trip.']

EKS_NOTE = (
    "Not part of the Lambda stack. docs/DEPLOY-EKS-VASOLAI.md describes an alternative in which eksctl creates a VPC "
    "(public and private subnets across the AZs it selects, a NAT gateway, and an EKS cluster with managed EC2 nodes). "
    "An internet-facing AWS Load Balancer Controller creates an Application Load Balancer and its security groups from "
    "deploy/k8s/ingress.yaml, with an ACM certificate in the cluster region. The WAF annotation is commented out. This path "
    "needs its own VPC inventory, which is not included here."
)


# ---------------------------------------------------------------------------
# Diagram
# ---------------------------------------------------------------------------
PALETTE = {
    "edge": "#E3EEFA",
    "compute": "#FFF1DB",
    "data": "#E2F5EA",
    "queue": "#FFF8C7",
    "ext": "#F1E7FA",
    "ident": "#E0F4F4",
    "ops": "#FBE4E4",
    "client": "#F4F4F5",
}
INK = "#1F2A36"
EDGE_COLOR = "#3B4A5A"


def _box(ax, x, y, w, h, title, sub, color, dashed=False):
    patch = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.2",
                           fc=PALETTE[color], ec=EDGE_COLOR, lw=1.0, linestyle="--" if dashed else "-")
    ax.add_patch(patch)
    ax.text(x + w / 2, y + h - 1.6, title, ha="center", va="top", fontsize=8.2, weight="bold", color=INK)
    ax.text(x + w / 2, y + h - 4.6, sub, ha="center", va="top", fontsize=6.3, color="#36444F", linespacing=1.25)
    return {"l": (x, y + h / 2), "r": (x + w, y + h / 2), "t": (x + w / 2, y + h), "b": (x + w / 2, y)}


def _arrow(ax, p, q, label="", dashed=False, rad=0.0):
    style = "arc3,rad=%s" % rad
    ax.add_patch(FancyArrowPatch(p, q, arrowstyle="-|>", mutation_scale=9, lw=0.9, color=EDGE_COLOR,
                                 linestyle="--" if dashed else "-", connectionstyle=style))
    if label:
        mx, my = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2
        ax.text(mx + 0.6, my + 0.6, label, fontsize=5.6, color="#4B5A69", ha="left", va="bottom")


def draw_diagram(path):
    fig, ax = plt.subplots(figsize=(11.5, 7.6), dpi=200)
    ax.set_xlim(-12, 122)
    ax.set_ylim(-26, 80)
    ax.set_aspect("equal")
    ax.axis("off")

    ax.add_patch(FancyBboxPatch((-10, -24), 130, 84, boxstyle="round,pad=0.2,rounding_size=2",
                                fc="none", ec="#7A8A99", lw=1.0, linestyle="--"))
    ax.text(-8, 58.5, "AWS region (ca-central-1 by default; set by the pipeline)", fontsize=7.2, color="#4B5A69",
            weight="bold", va="bottom")
    ax.text(-8, 79.2, "Global / edge: Route 53, CloudFront, AWS WAF (WAF and ACM certificate in us-east-1)",
            fontsize=7.2, color="#4B5A69", weight="bold")

    users = _box(ax, 0, 64, 18, 11, "Users", "Browser", "client")
    r53 = _box(ax, 24, 64, 18, 11, "Route 53", "DNS alias\n(optional)", "edge")
    cf = _box(ax, 46, 64, 24, 11, "CloudFront + WAF", "HTTPS, IP allowlist,\nmanaged rules, rate limit", "edge")

    cog = _box(ax, 2, 46, 26, 10, "No sign-in", "Open access. Limit by WAF\nallowlist (EnableWaf)", "ops", dashed=True)
    api = _box(ax, 46, 46, 24, 10, "API Gateway HTTP API", "No authorizer\n50 requests/s throttle", "edge")
    s3 = _box(ax, 80, 46, 36, 10, "S3 web bucket (private)", "Built React app, reached via OAC", "data")

    apil = _box(ax, 46, 30, 24, 10, "API Lambda (Java 21)", "Create trip, events, chat", "compute")
    sqs = _box(ax, 80, 30, 16, 10, "Jobs (SQS)", "visibility 960 s", "queue")
    dlq = _box(ax, 100, 30, 16, 10, "DLQ (SQS)", "after 3 failures", "queue")

    runs = _box(ax, 46, 14, 24, 10, "Trip runs (DynamoDB)", "Runs and event log, PITR", "data")
    worker = _box(ax, 80, 14, 16, 10, "Agent worker", "LangGraph4j, Java 21\n2048 MB, 900 s", "compute")

    bedrock = _box(ax, 46, -2, 24, 10, "Amazon Bedrock", "Chat model via\nConverse API", "ext")
    tavily = _box(ax, 80, -2, 16, 10, "Tavily + Secrets", "Search key in\nSecrets Manager", "ext")
    alarms = _box(ax, 100, -2, 16, 10, "CloudWatch", "Logs (30 days),\nalarms, X-Ray", "ops")

    alerts = _box(ax, 2, -20, 114, 9, "Alert topic (SNS)", "Alarms A1 to A3 email the operations address (AlertEmail)", "ops")

    _arrow(ax, users["r"], r53["l"], "DNS")
    _arrow(ax, r53["r"], cf["l"])
    _arrow(ax, (58, 64), (58, 56), "/api/*")
    bucket = _box(ax, 2, 30, 26, 10, "Prompt + policy bucket (S3)", "Prompts, runtime.yml, policy docs,\nembedding model", "data")
    ingest = _box(ax, 2, 14, 26, 10, "Ingest function (Java 21)", "Policy PDF and Excel to chunks,\nembedded in-process", "compute")
    chunks = _box(ax, 2, -2, 26, 10, "Policy chunks (DynamoDB)", "Chunk text and vectors", "data")
    _arrow(ax, api["b"], apil["t"], "")
    _arrow(ax, bucket["b"], ingest["t"], "upload")
    _arrow(ax, ingest["b"], chunks["t"], "chunks")
    _arrow(ax, apil["r"], sqs["l"], "job")
    _arrow(ax, sqs["b"], worker["t"], "")
    _arrow(ax, sqs["r"], dlq["l"], "")
    _arrow(ax, apil["b"], runs["t"], "")
    _arrow(ax, worker["l"], runs["r"], "events")
    _arrow(ax, worker["b"], tavily["t"], "")
    _arrow(ax, worker["b"], bedrock["r"], "", rad=0.15)
    _arrow(ax, dlq["b"], alarms["t"], "", dashed=True)
    _arrow(ax, alarms["b"], alerts["t"], "")
    _arrow(ax, cf["r"], s3["t"], "web", rad=0.0)

    fig.tight_layout(pad=0.2)
    fig.savefig(path, dpi=200, facecolor="white")
    plt.close(fig)


# ---------------------------------------------------------------------------
# Markdown
# ---------------------------------------------------------------------------
def _md_cell(text):
    return text.replace("|", "\\|").replace("\n", " ")


def write_markdown(path):
    lines = [f"# {TITLE}", "", SUMMARY, "", "![Architecture](diagrams/aws-architecture.png)", "",
             "## 1. Components (in creation order of the template)", ""]
    lines.append("| " + " | ".join(COLUMNS) + " |")
    lines.append("|" + "---|" * len(COLUMNS))
    for row in INVENTORY:
        lines.append("| " + " | ".join(_md_cell(c) for c in row) + " |")
    lines += ["", "## 2. What the stack does not create", "", "| Item | Status |", "|---|---|"]
    lines += [f"| {_md_cell(a)} | {_md_cell(b)} |" for a, b in NOT_CREATED]
    lines += ["", "## 3. Data flows", ""]
    lines += [f"- **{name}.** {text}" for name, text in FLOWS]
    lines += ["", "## 4. Security controls", ""] + [f"- {item}" for item in SECURITY]
    lines += ["", "## 5. Risks and open items", ""] + [f"{i}. {item}" for i, item in enumerate(RISKS, 1)]
    lines += ["", "## 6. Legacy EKS path", "", EKS_NOTE, ""]
    path.write_text("\n".join(lines), encoding="utf-8")


# ---------------------------------------------------------------------------
# PDF
# ---------------------------------------------------------------------------
def write_pdf(path):
    base = getSampleStyleSheet()
    h1 = ParagraphStyle("h1", parent=base["Heading1"], fontSize=17, leading=21, spaceAfter=6, textColor=colors.HexColor("#073F55"))
    h2 = ParagraphStyle("h2", parent=base["Heading2"], fontSize=12.5, leading=16, spaceBefore=10, spaceAfter=5, textColor=colors.HexColor("#0B5C78"))
    body = ParagraphStyle("body", parent=base["BodyText"], fontSize=9, leading=12.2)
    cell = ParagraphStyle("cell", parent=body, fontSize=6.6, leading=8.2)
    cell_head = ParagraphStyle("cellhead", parent=cell, fontName="Helvetica-Bold", textColor=colors.white)

    def P(text, style=cell):
        return Paragraph(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"), style)

    story = [Paragraph(TITLE, h1), Paragraph(SUMMARY, body), Spacer(1, 6),
             Image(str(DIAGRAM), width=180 * mm, height=180 * mm * 7.6 / 11.5), Spacer(1, 4),
             Paragraph("Figure 1. Request and failure paths. Dashed arrows are checks or alarms, not data writes.", cell)]

    story.append(Paragraph("1. Components", h2))
    widths = [9 * mm, 27 * mm, 26 * mm, 46 * mm, 25 * mm, 28 * mm, 34 * mm]
    rows = [[P(c, cell_head) for c in COLUMNS]] + [[P(c) for c in row] for row in INVENTORY]
    table = Table(rows, colWidths=widths, repeatRows=1)
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0B5C78")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F7F3EA")]),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#CFC6B4")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 3), ("RIGHTPADDING", (0, 0), (-1, -1), 3),
        ("TOPPADDING", (0, 0), (-1, -1), 2), ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    story.append(table)

    story.append(Paragraph("2. What the stack does not create", h2))
    story.append(Paragraph(
        "The template has no network layer. That is deliberate for a serverless design, but it means there is no VPC firewall to "
        "configure. Protection is CloudFront and WAF at the edge, API Gateway authorization and throttling, and IAM everywhere else.", body))
    story.append(Spacer(1, 4))
    not_rows = [[P("Item", cell_head), P("Status", cell_head)]] + [[P(a), P(b)] for a, b in NOT_CREATED]
    nt = Table(not_rows, colWidths=[50 * mm, 128 * mm], repeatRows=1)
    nt.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0B5C78")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F7F3EA")]),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#CFC6B4")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(nt)

    def bullets(items):
        return ListFlowable([ListItem(Paragraph(i, body), leftIndent=12) for i in items], bulletType="bullet", start="•", leftIndent=12)

    story.append(Paragraph("3. Data flows", h2))
    story.append(bullets([f"<b>{name}.</b> {text}" for name, text in FLOWS]))
    story.append(Paragraph("4. Security controls", h2))
    story.append(bullets(SECURITY))
    story.append(Paragraph("5. Risks and open items", h2))
    story.append(ListFlowable([ListItem(Paragraph(i, body), leftIndent=14) for i in RISKS], bulletType="1", leftIndent=14))
    story.append(KeepTogether([Paragraph("6. Legacy EKS path", h2), Paragraph(EKS_NOTE, body)]))

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 7)
        canvas.setFillColor(colors.HexColor("#6B8A96"))
        canvas.drawString(14 * mm, 8 * mm, TITLE)
        canvas.drawRightString(A4[0] - 14 * mm, 8 * mm, f"Page {doc.page}")
        canvas.restoreState()

    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=14 * mm, rightMargin=14 * mm,
                            topMargin=14 * mm, bottomMargin=16 * mm, title=TITLE, author="VamsDest")
    doc.build(story, onFirstPage=footer, onLaterPages=footer)


def main():
    DIAGRAM.parent.mkdir(parents=True, exist_ok=True)
    draw_diagram(DIAGRAM)
    write_markdown(MARKDOWN)
    write_pdf(PDF)
    print(f"Wrote {DIAGRAM}\nWrote {MARKDOWN}\nWrote {PDF}")


if __name__ == "__main__":
    main()
