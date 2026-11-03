"""Renders the architecture diagrams used by docs/VamsDest-AWS-Architecture.docx.

Run from the repository root:  python docs/diagrams/build_diagrams.py
Writes PNG files next to this script. Requires matplotlib.
"""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Polygon  # noqa: E402

OUT = Path(__file__).resolve().parent

PALETTE = {
    "client": "#F4F4F5",
    "edge": "#E3EEFA",
    "compute": "#FFF1DB",
    "data": "#E2F5EA",
    "queue": "#FFF8C7",
    "ext": "#F1E7FA",
    "ops": "#FBE4E4",
    "ident": "#E0F4F4",
    "pipe": "#E9EEF6",
}
INK = "#1F2A36"
EDGE = "#3B4A5A"
NOTE = "#4B5A69"


def box(ax, x, y, w, h, title, sub="", color="compute", title_size=9.0, sub_size=6.8):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.25,rounding_size=1.0",
                                fc=PALETTE[color], ec=EDGE, lw=1.1))
    if sub:
        ax.text(x + w / 2, y + h - 1.4, title, ha="center", va="top", fontsize=title_size,
                weight="bold", color=INK)
        ax.text(x + w / 2, y + h - 4.4, sub, ha="center", va="top", fontsize=sub_size, color="#36444F",
                linespacing=1.25)
    else:
        ax.text(x + w / 2, y + h / 2, title, ha="center", va="center", fontsize=title_size,
                weight="bold", color=INK)


def arrow(ax, x1, y1, x2, y2, label="", lx=None, ly=None, dashed=False, color=EDGE, size=6.6):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=11,
                                 color=color, lw=1.0, linestyle="--" if dashed else "-",
                                 shrinkA=0, shrinkB=0))
    if label:
        mx = (x1 + x2) / 2 if lx is None else lx
        my = (y1 + y2) / 2 if ly is None else ly
        ax.text(mx, my, label, fontsize=size, ha="center", va="center", color=INK,
                bbox=dict(fc="white", ec="none", pad=0.6, alpha=0.9))


def canvas(width, height, title, subtitle=""):
    fig, ax = plt.subplots(figsize=(width, height), dpi=170)
    ax.set_xlim(0, 150)
    ax.set_ylim(0, 100)
    ax.axis("off")
    fig.patch.set_facecolor("white")
    ax.text(1, 99.4, title, fontsize=13, weight="bold", color=INK, va="top")
    if subtitle:
        ax.text(1, 95.4, subtitle, fontsize=8.2, color=NOTE, va="top")
    return fig, ax


def save(fig, name):
    path = OUT / name
    fig.savefig(path, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print("wrote", path.name)


# ---------------------------------------------------------------- Figure 1: system context
def system_context():
    fig, ax = canvas(15, 10, "Figure 1. System context (high level)",
                     "Users reach one HTTPS domain; AWS services do the work; Claude and Tavily are external.")

    box(ax, 1, 44, 17, 22, "Traveler", "Web browser\nSigns in with Cognito\n(PKCE, no client secret)", "client")

    box(ax, 23, 84, 36, 9, "Route 53 + ACM", "Public DNS record; TLS certificate in us-east-1", "edge")
    box(ax, 23, 66, 36, 13, "CloudFront (+ optional WAF)",
        "/* -> S3 web bucket    /api/* -> HTTP API\nSecurity headers, TLS 1.2+, no caching for /api", "edge")
    box(ax, 23, 38, 36, 13, "Cognito user pool + Hosted UI",
        "Email sign-in, invite-only\nIssues ID tokens (audience = app client)", "ident")

    box(ax, 66, 78, 30, 9, "S3 web bucket", "Built React app; private, OAC only", "data")
    box(ax, 66, 62, 30, 11, "HTTP API", "JWT authorizer on every route", "edge")
    box(ax, 66, 46, 30, 11, "DynamoDB: runs table", "Trip metadata, owner, status,\nordered event log, TTL", "data")
    box(ax, 66, 28, 30, 12, "Worker Lambda", "Java 21, 1024 MB, 900 s\nHarness: 4 specialists,\ncoordinator, chat", "compute")
    box(ax, 66, 8, 30, 10, "Amazon Bedrock", "Claude via Converse API\n(optional guardrail)", "ext")
    box(ax, 30, 8, 30, 12, "Tavily search API", "Public web search\nAPI key from Secrets Manager", "ext")
    box(ax, 30, 26, 30, 9, "Secrets Manager", "Tavily key; worker role only", "ops")

    box(ax, 104, 62, 28, 11, "API Lambda", "Java 21, 512 MB, 10 s\nValidate, write run, queue job", "compute")
    box(ax, 104, 40, 28, 11, "SQS job queue", "Trip and chat jobs\nDLQ after 3 failed receives", "queue")
    box(ax, 104, 22, 28, 9, "Dead-letter queue", "Failed jobs kept 14 days", "queue")
    box(ax, 104, 4, 28, 10, "CloudWatch + SNS", "Logs (30 days), alarms,\nemail alerts", "ops")

    arrow(ax, 18, 62, 23, 72, "HTTPS", lx=19.8, ly=69)
    arrow(ax, 18, 50, 23, 46, "Hosted UI", lx=20.6, ly=42.2, size=5.8)
    arrow(ax, 59, 80, 66, 82, "/*", lx=62.5, ly=84.2)
    arrow(ax, 59, 72, 66, 68, "/api/*", lx=62.5, ly=73.2)
    arrow(ax, 66, 64, 59, 50, "JWT check", lx=59.5, ly=58.5, size=6.0)
    arrow(ax, 96, 67, 104, 67, "invoke", lx=100, ly=70.5, size=6.0)
    arrow(ax, 110, 62, 96, 52, "read / write", lx=108.5, ly=55.5, size=6.0)
    arrow(ax, 118, 62, 118, 51, "send job", lx=122.6, ly=56.5, size=6.0)
    arrow(ax, 104, 45, 96, 34, "consume\n(batch 1)", lx=98.6, ly=42.5, size=5.9)
    arrow(ax, 118, 40, 118, 31, "after 3 tries", lx=124.8, ly=35.5, dashed=True, color="#8A6D00", size=5.9)
    arrow(ax, 81, 28, 81, 18, "Converse", lx=85.8, ly=23, size=6.0)
    arrow(ax, 81, 40, 81, 46, "events, status", lx=88.6, ly=43.2, size=5.9)
    arrow(ax, 66, 31, 60, 18, "search", lx=60.2, ly=24.6, size=6.0)
    arrow(ax, 66, 34, 60, 31, "key", lx=63.2, ly=33.3, size=5.9)

    save(fig, "fig1-system-context.png")


# ---------------------------------------------------------------- Figure 2: delivery pipeline
def pipeline():
    fig, ax = canvas(15, 7.4, "Figure 2. Delivery pipeline (high level)",
                     "Verify on every change; deploy to dev from main; prod only by manual run with reviewer approval.")

    box(ax, 1, 40, 16, 22, "Developer", "Pushes to main\nor starts a manual run\n(dev or prod)", "client")
    box(ax, 22, 40, 20, 22, "GitHub repository", "Source, infra/template.yaml,\nscripts/, workflow file", "pipe")
    box(ax, 47, 56, 36, 24, "Job: verify", "mvn -B verify (backend tests)\nnpm ci and npm run build (web)\ncfn-lint infra/template.yaml",
        "pipe")
    box(ax, 47, 22, 36, 26, "Job: deploy (environment)",
        "Assume deploy role via OIDC\nscripts/package-lambda.sh\nsam deploy (change set, then apply)\nscripts/deploy-web.sh\nSmoke test: 200 and 401 checks", "pipe")
    box(ax, 91, 56, 27, 24, "AWS account", "IAM role trusts only this repo\nand this environment name\nNo long-lived keys in GitHub", "ident")
    box(ax, 91, 22, 27, 26, "CloudFormation stack", "Lambda functions, HTTP API,\nCognito, SQS, DynamoDB, S3,\nCloudFront, alarms, Route 53", "edge")
    box(ax, 124, 40, 25, 22, "Live site", "https://<app domain>\nWeb app and API on one domain\nSmoke-tested after deploy", "client")

    arrow(ax, 17, 51, 22, 51, "push / PR", lx=19.5, ly=54.5)
    arrow(ax, 42, 55, 47, 66, "trigger", lx=45.7, ly=61)
    arrow(ax, 83, 66, 91, 66, "OIDC", lx=87, ly=68.8)
    arrow(ax, 83, 33, 91, 33, "deploy", lx=87, ly=35.8)
    arrow(ax, 118, 42, 124, 50, "", color=EDGE)
    arrow(ax, 118, 36, 124, 46, "publish", lx=122.4, ly=38.5)
    arrow(ax, 65, 56, 65, 48, "needs: verify", lx=68.8, ly=52, size=6.0)

    save(fig, "fig2-delivery-pipeline.png")


# ---------------------------------------------------------------- sequence helper
def sequence(ax, actors, messages, top=86, step=4.6, actor_w=17.0, x0=8.0, span=None):
    span = span or (150 - 2 * x0)
    gap = span / (len(actors) - 1) if len(actors) > 1 else 0
    xs = {name: x0 + i * gap for i, name in enumerate(actors)}
    bottom = top - 8 - step * len(messages) - 2
    for name, x in xs.items():
        ax.add_patch(FancyBboxPatch((x - actor_w / 2, top - 4), actor_w, 5.2,
                                    boxstyle="round,pad=0.2,rounding_size=0.8", fc="#E8EEF5", ec=EDGE, lw=1.0))
        ax.text(x, top - 1.4, name, ha="center", va="center", fontsize=7.2, weight="bold", color=INK)
        ax.plot([x, x], [top - 4.3, bottom], color="#9AA7B4", lw=0.9, linestyle=":")
    for index, (src, dst, label, dashed) in enumerate(messages):
        y = top - 8 - index * step
        arrow(ax, xs[src], y, xs[dst], y, "", dashed=dashed)
        ax.text((xs[src] + xs[dst]) / 2, y + 1.0, label, fontsize=6.4, ha="center", va="bottom", color=INK,
                bbox=dict(fc="white", ec="none", pad=0.2, alpha=0.9))
    return xs


# ---------------------------------------------------------------- Figure 3: sign-in and create trip
def request_sequence():
    fig, ax = canvas(15, 11, "Figure 3. Sign-in, create a trip, and poll progress (low level)",
                     "The synchronous part is short. Research happens later, in the worker.")
    actors = ["Browser", "CloudFront", "Cognito", "HTTP API", "API Lambda", "DynamoDB", "SQS"]
    messages = [
        ("Browser", "Cognito", "1. /oauth2/authorize (PKCE challenge, state)", False),
        ("Cognito", "Browser", "2. Redirect back with code and state", True),
        ("Browser", "Cognito", "3. /oauth2/token (code + code_verifier)", False),
        ("Cognito", "Browser", "4. ID token (aud = app client), 60 minutes", True),
        ("Browser", "CloudFront", "5. POST /api/trips with Authorization: Bearer", False),
        ("CloudFront", "HTTP API", "6. Forward; auth header kept; no caching", False),
        ("HTTP API", "Cognito", "7. Verify signature, issuer, audience", True),
        ("HTTP API", "API Lambda", "8. Invoke with JWT claims (sub = owner)", False),
        ("API Lambda", "API Lambda", "9. Validate TripRequest; check dates", False),
        ("API Lambda", "DynamoDB", "10. Put META: owner, request, status = planning", False),
        ("API Lambda", "SQS", "11. Send {type: trip, tripId}", False),
        ("API Lambda", "Browser", "12. 202 Accepted {tripId, eventsUrl}", True),
        ("Browser", "CloudFront", "13. GET /events?after=0 (repeats every 1.5 s)", False),
        ("API Lambda", "DynamoDB", "14. Owner check, then Query EVT# range", False),
        ("API Lambda", "Browser", "15. {status, nextAfter, events[]}", True),
    ]
    sequence(ax, actors, messages, top=92, step=4.9, x0=9, span=132, actor_w=14.5)
    ax.text(1, 2.5, "Steps 13 to 15 repeat until status leaves 'planning' and no chat question is pending. "
                    "Other users' trips return 404.", fontsize=7.0, color=NOTE)
    save(fig, "fig3-sign-in-and-create-trip.png")


# ---------------------------------------------------------------- Figure 4: research run
def research_sequence():
    fig, ax = canvas(15, 11.5, "Figure 4. Research run inside the worker (low level)",
                     "Four specialists run in parallel on a 4-thread pool; each drives its own tool loop.")
    actors = ["SQS", "Worker", "DynamoDB", "Specialist (x4)", "Bedrock Converse", "Tavily"]
    messages = [
        ("SQS", "Worker", "1. Deliver message (batch size 1)", False),
        ("Worker", "DynamoDB", "2. Get run; continue only if status = planning", False),
        ("Worker", "Specialist (x4)", "3. Start 4 tasks: destination, stay, transport, taxi", False),
        ("Specialist (x4)", "DynamoDB", "4. Append agent_status: running", False),
        ("Specialist (x4)", "Bedrock Converse", "5. Converse: role, task, web_search tool", False),
        ("Bedrock Converse", "Specialist (x4)", "6. stop_reason = tool_use (query)", True),
        ("Specialist (x4)", "DynamoDB", "7. Append agent_tool with the query text", False),
        ("Specialist (x4)", "Tavily", "8. POST /search (5 results, 15 s timeout)", False),
        ("Tavily", "Specialist (x4)", "9. Titles, URLs, excerpts (untrusted data)", True),
        ("Specialist (x4)", "Bedrock Converse", "10. Converse again with tool_result", False),
        ("Bedrock Converse", "Specialist (x4)", "11. stop_reason = end_turn (brief)", True),
        ("Specialist (x4)", "DynamoDB", "12. Append agent_result with its sources", False),
        ("Worker", "Bedrock Converse", "13. Coordinator merges 4 briefs (no tools)", False),
        ("Worker", "DynamoDB", "14. Append trip_summary; set status; trip_complete", False),
    ]
    sequence(ax, actors, messages, top=92, step=5.1, x0=12, span=126, actor_w=17)
    ax.text(1, 2.5, "A failed specialist appends agent_result with state = failed; the run ends 'partial'. "
                    "The SDK retries throttling only within its own limits.", fontsize=7.0, color=NOTE)
    save(fig, "fig4-research-run-sequence.png")


# ---------------------------------------------------------------- Figure 5: harness loop
def harness_loop():
    fig, ax = canvas(13, 11.5, "Figure 5. Agentic harness loop (low level)",
                     "One specialist run, bounded by turns and by a per-agent search budget.")

    def diamond(x, y, w, h, text):
        pts = [(x, y + h / 2), (x + w / 2, y + h), (x + w, y + h / 2), (x + w / 2, y)]
        ax.add_patch(Polygon(pts, closed=True, fc="#FFF8C7", ec=EDGE, lw=1.1))
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=7.0, color=INK)

    box(ax, 40, 88, 36, 6, "Start: role + task", "system = role + safety rules; user = task", "client", sub_size=6.5)
    diamond(40, 73, 36, 11, "turn <= MAX_TURNS ?")
    box(ax, 4, 74, 26, 9, "Error: turn limit", "Throws; the specialist\nis marked failed", "ops", sub_size=6.5)
    box(ax, 40, 58, 36, 9, "Converse (turn n)", "modelId, guardrail, maxTokens;\ntools only while budget > 0", "ext", sub_size=6.5)
    diamond(40, 42, 36, 11, "stop_reason ==\ntool_use ?")
    box(ax, 4, 40, 26, 14, "End: end_turn", "Return text and the sources\nlist; agent completed", "data", sub_size=6.5)
    box(ax, 40, 28, 36, 9, "For each toolUse block", "Unknown tool or empty query\nbecomes an error result", "compute", sub_size=6.5)
    diamond(40, 13, 36, 11, "valid and\nsearches < budget ?")
    box(ax, 4, 13, 26, 11, "Error result", "Budget spent: answer with\nthe evidence already gathered", "ops", sub_size=6.5)
    box(ax, 100, 11, 42, 14, "Search via Tavily",
        "Query capped at 300 chars; 5 results.\nEvents appended. A provider error becomes\nan error result and the loop continues", "compute", sub_size=6.3)
    box(ax, 40, 0.5, 36, 8, "Append tool results", "One user message; back to turn check", "compute", sub_size=6.3)

    arrow(ax, 58, 88, 58, 84)
    arrow(ax, 58, 73, 58, 67, "yes", lx=61.5, ly=70.2)
    arrow(ax, 40, 78.5, 30, 78.5, "no", lx=35, ly=80.6)
    arrow(ax, 58, 58, 58, 53)
    arrow(ax, 58, 42, 58, 37, "yes", lx=61.5, ly=39.5)
    arrow(ax, 40, 47.5, 30, 47.5, "no", lx=35, ly=49.6)
    arrow(ax, 58, 28, 58, 24)
    arrow(ax, 40, 18.5, 30, 18.5, "no", lx=35, ly=20.6)
    arrow(ax, 76, 18.5, 100, 18.5, "yes", lx=88, ly=20.6)
    arrow(ax, 17, 13, 17, 8.5)
    arrow(ax, 17, 8.5, 40, 4.5)
    arrow(ax, 121, 11, 121, 8.5)
    arrow(ax, 121, 8.5, 76, 4.5)
    ax.text(1.5, -3.5, "Limits: AGENT_MAX_TURNS (default 8); AGENT_MAX_SEARCHES (4 per specialist, 2 per chat); "
                       "AGENT_MAX_TOKENS (1500 per call).", fontsize=7.0, color=NOTE)

    save(fig, "fig5-agentic-harness-loop.png")


# ---------------------------------------------------------------- Figure 6: data model
def data_model():
    fig, ax = canvas(15, 8.6, "Figure 6. DynamoDB run store layout (low level)",
                     "One table. The event sequence comes from an atomic counter on META, so parallel agents never collide.")

    rows = [
        ("pk", "sk", "Attributes", "Written by", "Read by"),
        ("RUN#<runId>", "META", "ownerId, request (JSON), createdAt, status (planning | completed | partial),\neventCount (atomic ADD), expiresAt (TTL)",
         "API Lambda (create); worker (status, counter)", "API (owner check); worker"),
        ("RUN#<runId>", "EVT#0000000001", "data = TripEvent JSON {seq, type, agentId, state, message, sources, ...}\nexpiresAt",
         "Worker: each progress step", "API: events?after=n"),
        ("RUN#<runId>", "EVT#0000000002", "data = next event; zero-padded sequence keeps BETWEEN ordering correct",
         "Worker", "API"),
        ("RUN#<runId>", "EVT#...", "chat_answer events carry correlationId = requestId",
         "Worker (chat job)", "API"),
    ]
    col_x = [2, 24, 46, 104, 126]
    col_w = [22, 22, 58, 22, 22]
    row_h = [4.4, 10.5, 8.2, 6.4, 6.4]
    y = 84
    for r, row in enumerate(rows):
        h = row_h[r]
        for c, cell in enumerate(row):
            fc = "#D9E4F2" if r == 0 else ("#F7FBF8" if c < 2 else "white")
            ax.add_patch(FancyBboxPatch((col_x[c], y - h), col_w[c], h, boxstyle="square,pad=0",
                                        fc=fc, ec="#8C9AAA", lw=0.8))
            ax.text(col_x[c] + 0.8, y - h / 2, cell, fontsize=6.8 if r else 7.4,
                    weight="bold" if r == 0 else "normal", va="center", color=INK, linespacing=1.3)
        y -= h
    ax.text(2, y - 3, "Queries: get META with ConsistentRead. Events: Query(pk = :pk AND sk BETWEEN EVT#(after+1) "
                      "AND EVT#9999999999), paginated.", fontsize=7.0, color=NOTE)
    ax.text(2, y - 6.5, "Retention: expiresAt is set on META and on each event (RUN_TTL_HOURS, default 24). "
                        "Point-in-time recovery and SSE are on.", fontsize=7.0, color=NOTE)
    ax.text(2, y - 10, "Owner isolation: every read path first compares ownerId with the JWT sub claim; "
                       "mismatches return 404.", fontsize=7.0, color=NOTE)
    save(fig, "fig6-data-model.png")


# ---------------------------------------------------------------- Figure 7: failure handling
def failure_flow():
    fig, ax = canvas(14, 9.5, "Figure 7. Failure handling and retry paths (low level)",
                     "Expected failures become visible events; only infrastructure faults go back to the queue.")

    box(ax, 4, 80, 28, 8, "Message received", "SQS delivers one job (batch 1)", "queue", sub_size=6.5)
    box(ax, 40, 80, 30, 8, "Run exists?", "Lookup by tripId (no owner check)", "compute", sub_size=6.5)
    box(ax, 78, 80, 30, 8, "Log and drop", "Unknown run: nothing to do;\nmessage is deleted", "ops", sub_size=6.5)

    box(ax, 40, 64, 30, 8, "Status == planning?", "Redelivered finished run is skipped", "compute", sub_size=6.5)
    box(ax, 78, 64, 30, 8, "Skip (log info)", "Completed or partial run is not\nresearched twice", "data", sub_size=6.5)

    box(ax, 40, 46, 30, 10, "Run specialists + coordinator", "Agent faults become events", "compute", sub_size=6.5)

    box(ax, 4, 26, 28, 10, "Specialist fails", "agent_result state = failed;\nother agents continue", "ops", sub_size=6.5)
    box(ax, 40, 26, 30, 10, "Coordinator fails", "Canned summary; run partial;\nevents still shown", "ops", sub_size=6.5)
    box(ax, 78, 26, 30, 10, "Infrastructure error", "DynamoDB or SDK fault outside\nan agent (for example, auth)", "ops", sub_size=6.5)

    box(ax, 4, 8, 28, 10, "Run finishes", "setStatus completed | partial;\ntrip_complete event", "data", sub_size=6.5)
    box(ax, 40, 8, 30, 10, "Job catches it", "Run set partial; trip_complete\n'stopped unexpectedly'", "compute", sub_size=6.5)
    box(ax, 78, 8, 30, 10, "Rethrow", "Message returns to queue after\n960 s visibility timeout", "queue", sub_size=6.5)
    box(ax, 112, 8, 34, 10, "After 3 receives: DLQ", "DLQ alarm fires; redrive after fix", "ops", sub_size=6.5)

    arrow(ax, 32, 84, 40, 84)
    arrow(ax, 70, 84, 78, 84, "no", lx=74.2, ly=86.4)
    arrow(ax, 54, 80, 54, 72, "yes", lx=57.5, ly=76)
    arrow(ax, 70, 68, 78, 68, "no", lx=74.2, ly=70.4)
    arrow(ax, 54, 64, 54, 56, "yes", lx=57.5, ly=60)
    arrow(ax, 40, 51, 32, 36, "agent error", lx=32.5, ly=44.5, size=6.2)
    arrow(ax, 54, 46, 54, 36, "merge", lx=57.5, ly=41, size=6.2)
    arrow(ax, 70, 50, 78, 36, "throws", lx=76.6, ly=45.5, size=6.2)
    arrow(ax, 18, 26, 18, 18, "all done", lx=21.8, ly=22, size=6.2)
    arrow(ax, 54, 26, 54, 18, "catch", lx=57.5, ly=22, size=6.2)
    arrow(ax, 93, 26, 93, 18, "throw", lx=96.6, ly=22, size=6.2)
    arrow(ax, 108, 13, 112, 13, "3 times", lx=110, ly=16.8, size=6.0)

    save(fig, "fig7-failure-handling.png")


if __name__ == "__main__":
    system_context()
    pipeline()
    request_sequence()
    research_sequence()
    harness_loop()
    data_model()
    failure_flow()
