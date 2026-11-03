# VamsDest: AWS Deployment Guide

This guide deploys VamsDest to AWS with Lambda functions, an HTTP API, a job queue, DynamoDB, and a CloudFront-served web app. It covers one-time setup, deploying through the GitHub Actions pipeline, deploying by hand, and running the deployment through an agentic harness (an AI agent that executes these steps and stops at the gates).

The architecture is in [VamsDest-AWS-Architecture.docx](VamsDest-AWS-Architecture.docx), with high-level and low-level diagrams.

> **Scope of verification.** The template passes `cfn-lint`, the backend and frontend build and test cleanly, and the Lambda package contains the handler classes. The stack has not been deployed to a live AWS account from this repository yet. Treat the first deployment to any account as a validation run and follow section 9.

---

## 1. What gets deployed

| Resource | Purpose |
|---|---|
| CloudFront distribution + ACM certificate + Route 53 records | Single HTTPS origin for the web app (`/`) and API (`/api/*`). Security headers, TLS 1.2+, optional WAF |
| S3 bucket (private, OAC) | Built React app (`index.html`, `assets/`) |
| Cognito user pool + Hosted UI app client | Sign-in with email; invite-only by default (`AllowAdminCreateUserOnly`) |
| HTTP API (API Gateway v2) | Routes `POST /api/trips`, `GET /api/trips/{tripId}/events`, `POST /api/trips/{tripId}/chat`; JWT authorizer on every route |
| API Lambda (`ApiHandler`, Java 21, 512 MB) | Validates requests, writes the trip run, queues work, returns polled events |
| SQS queue + dead-letter queue | Decouples requests from research; three failed receives move a job to the DLQ |
| Worker Lambda (`WorkerHandler`, Java 21, 1024 MB, 900 s) | Runs the agentic harness: four Claude specialists on Bedrock, Tavily searches, coordinator merge, chat answers |
| DynamoDB table `vamsdest-<env>-runs` | Run metadata, owner, status, and the ordered event log; TTL removes runs after `RunRetentionHours` |
| Secrets Manager secret (created outside the stack) | Tavily API key, read by the worker only |
| CloudWatch log groups, alarms, SNS topic | 30-day logs; alarms for dead-letter messages, worker errors, and API 5xx |
| WAF web ACL (optional, `EnableWaf=true`) | AWS managed common and known-bad-input rules, plus a per-IP rate limit |

### Request flow in one paragraph

The browser signs in through Cognito Hosted UI (PKCE) and sends the ID token as a bearer token. `POST /api/trips` creates a `planning` run in DynamoDB and sends a `trip` job to SQS. The worker picks up the job, runs the four specialist agents in parallel (each one decides its own searches and reads results through the `web_search` tool), writes each progress step to the run's event log, merges the briefs, and sets the run to `completed` or `partial`. The browser polls `GET /events?after=<cursor>` every 1.5 seconds and renders events in order. Chat questions follow the same pattern: `POST /chat` queues a `chat` job, and the answer arrives as a `chat_answer` event that carries the request ID.

### What changed from the local version

These changes are required for Lambda. They are not optional:

- **Run state moved from memory to DynamoDB.** Lambda instances don't share memory, so the old in-memory registry can't work here. The local Spring server still uses an in-memory store behind the same `RunStore` interface.
- **Server-sent events replaced by polling.** API Gateway and Lambda don't hold a long-lived stream open for ten minutes. The event log plus a `nextAfter` cursor gives the same ordered progress.
- **Work is queued, not run inline.** Research takes minutes. The API returns `202` immediately, and the worker runs the job.
- **Authentication is a bearer token from Cognito.** The old Spring OIDC session and CSRF flow don't apply to an HTTP API. CSRF is not needed for bearer tokens. The `CsrfController` was moved to `.backup-pre-live/removed/`.
- **The Spring `prod` profile is not used on AWS.** The `application-prod.yml` OIDC configuration remains for container experiments only. Do not expose the Docker image to the internet: it runs the local profile, which has no authentication.

---

## 2. Prerequisites

Tools (versions tested or minimum):

| Tool | Version | Used for |
|---|---|---|
| AWS CLI v2 | 2.15+ | Secrets, Cognito users, invalidations, outputs |
| AWS SAM CLI | 1.120+ | `sam deploy` |
| Java (Temurin) | 21 | Maven build |
| Maven | 3.9+ | Build and tests |
| Node.js | 22+ | Web build |
| Python | 3.10+ with `cfn-lint` | Template lint (`pip install cfn-lint`) |
| Git Bash or WSL (Windows) | any | Runs `scripts/*.sh` |

Accounts and access:

- An AWS account with administrator rights for the first deployment. Afterwards, use the least-privilege deploy role (section 3.4).
- A public Route 53 hosted zone for your domain, for example `example.com`.
- Bedrock model access enabled in your region for the model you choose (the default is `us.anthropic.claude-sonnet-5-5`). Access is granted per account in the Bedrock console and varies by region.
- A Tavily account and API key.
- A GitHub repository with admin rights to create environments and variables.

Regions: choose one region for everything except the ACM certificate and WAF, which must be in **us-east-1** because CloudFront requires it. The stack's default `region` is `us-east-1`, so the simplest setup is to deploy there.

---

## 3. One-time setup

### 3.1 Route 53 and certificate

1. Confirm the hosted zone exists and note its ID (`Z...`):
   ```powershell
   aws route53 list-hosted-zones-by-name --dns-name example.com
   ```
2. Request a certificate in us-east-1 for the app hostname, for example `travel.example.com`, using DNS validation. Add the validation CNAME records in the same zone, and wait for status `ISSUED`:
   ```powershell
   aws acm request-certificate --region us-east-1 --domain-name travel.example.com --validation-method DNS
   aws acm describe-certificate --region us-east-1 --certificate-arn <arn> --query "Certificate.Status"
   ```
   Keep the certificate ARN. You will set it as `CERTIFICATE_ARN`.

### 3.2 Tavily secret

Put the key in a temporary file, create the secret from the file, then delete the file. This keeps the key out of shell history and command lines:

```bash
# Git Bash. Paste the key into the file with your editor; do not echo it on the command line.
notepad tavily.key
aws secretsmanager create-secret --region us-east-1 --name vamsdest/dev/tavily-api-key   --description "Tavily API key for VamsDest research worker"   --secret-string file://tavily.key
rm tavily.key
```

Keep the ARN from the output. You will set it as `TAVILY_SECRET_ARN`.

Rotation is manual: `aws secretsmanager put-secret-value --secret-id <arn> --secret-string file://tavily.key`, then delete the file. The worker reads the secret at cold start, so a new value takes effect for new worker instances. Force one by changing any worker environment setting in a deploy.

### 3.3 Bedrock

1. In the Bedrock console for your region, request access to the Claude model you intend to use.
2. Confirm the inference profile ID (for example `us.anthropic.claude-sonnet-5-5`) and set it as `BEDROCK_MODEL_ID`.
3. Optional: create a Bedrock Guardrail and publish a version. Set `BEDROCK_GUARDRAIL_ID` and `BEDROCK_GUARDRAIL_VERSION`. Set both or neither: the worker refuses to start if only the ID is set, and the failure appears in its logs at the first invocation.

### 3.4 GitHub OIDC and the deploy role

The pipeline assumes an IAM role through OIDC. No AWS keys are stored in GitHub.

1. Create the OIDC identity provider once per account:
   - URL: `https://token.actions.githubusercontent.com`
   - Audience: `sts.amazonaws.com`
2. Create a role per environment, for example `vamsdest-deploy-dev`, with this trust policy. Replace the account ID, owner, repository, and environment name:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
       "Action": "sts:AssumeRoleWithWebIdentity",
       "Condition": {
         "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
         "StringLike": { "token.actions.githubusercontent.com:sub": "repo:<OWNER>/<REPO>:environment:dev" }
       }
     }]
   }
   ```

3. Permissions: attach a policy that covers only what the stack creates. Start with CloudFormation, Lambda, API Gateway (`apigateway:*` scoped to the API), Cognito, DynamoDB, SQS, SNS, S3 (buckets named `vamsdest-*`), CloudFront, WAFv2, Route 53 record sets in your zone, CloudWatch Logs and alarms, Secrets Manager `GetSecretValue` for the Tavily ARN, and `iam:PassRole` restricted to the roles SAM creates. Tighten this after the first successful deploy by reviewing CloudTrail for denied calls.

   The `sam deploy` step creates IAM roles for the functions, so the deploy role needs `iam:CreateRole`, `iam:AttachRolePolicy`, `iam:PutRolePolicy`, and `iam:PassRole` on `arn:aws:iam::<ACCOUNT_ID>:role/vamsdest-*`, plus `iam:DeleteRole*` for rollbacks.

4. Use a separate role for `prod` with a trust condition on `environment:prod`.

### 3.5 GitHub environments and variables

In GitHub, create environments `dev` and `prod` (Settings > Environments). For `prod`, add required reviewers, so every production deploy waits for a human. Set these variables on each environment:

| Variable | Example | Notes |
|---|---|---|
| `AWS_REGION` | `us-east-1` | Must be us-east-1 if `ENABLE_WAF=true` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::123456789012:role/vamsdest-deploy-dev` | From 3.4 |
| `APP_DOMAIN_NAME` | `travel.example.com` | Must match the certificate |
| `CERTIFICATE_ARN` | `arn:aws:acm:us-east-1:...` | From 3.1 |
| `HOSTED_ZONE_ID` | `Z0123456789ABC` | From 3.1 |
| `COGNITO_DOMAIN_PREFIX` | `vamsdest-dev-auth` | Globally unique, lowercase |
| `TAVILY_SECRET_ARN` | `arn:aws:secretsmanager:...` | From 3.2 |
| `ALERT_EMAIL` | `ops@example.com` | Optional; empty disables the subscription |
| `BEDROCK_MODEL_ID` | `us.anthropic.claude-sonnet-5-5` | From 3.3 |
| `BEDROCK_GUARDRAIL_ID` | (empty or ID) | From 3.3 |
| `BEDROCK_GUARDRAIL_VERSION` | (empty or version) | From 3.3 |
| `WORKER_CONCURRENCY` | `5` | Caps parallel research runs and therefore spend |
| `ENABLE_WAF` | `false` | `true` only in us-east-1 |

No secret values belong in GitHub variables. The Tavily key stays in Secrets Manager.

---

## 4. Deploy with the pipeline

The workflow is `.github/workflows/deploy.yml`.

- Every push and pull request runs `verify`: backend tests (`mvn verify`), frontend build, and `cfn-lint`.
- A push to `main` runs `deploy` against `dev`.
- A manual run (Actions > deploy-aws > Run workflow) lets you choose `dev` or `prod`.

The deploy job:

1. Builds the Lambda package (`scripts/package-lambda.sh`), which produces `build/lambda/`.
2. Runs `sam deploy` with `infra/samconfig.toml` and the environment variables as parameter overrides. The change set is applied without confirmation in CI, but the `prod` environment's required reviewers approve the job first.
3. Builds the web app against the Cognito outputs and publishes it (`scripts/deploy-web.sh`), then invalidates `/index.html` in CloudFront.
4. Smoke-tests the site and confirms that an unauthenticated `POST /api/trips` returns `401`.

Watch the run in the Actions tab. The first deploy takes about 15 to 25 minutes, mostly CloudFront distribution creation.

---

## 5. Deploy by hand (no pipeline)

Run these from the repository root in Git Bash, or in WSL. Replace the placeholders with your values.

```bash
# 1. Verify
mvn -B verify
npm ci && npm run build
cfn-lint infra/template.yaml

# 2. Package the Lambda code
scripts/package-lambda.sh

# 3. Deploy the stack (dev)
cd infra
sam deploy --config-env dev --region us-east-1 \
  --parameter-overrides \
    AppDomainName=travel.example.com \
    CertificateArn=arn:aws:acm:us-east-1:123456789012:certificate/... \
    HostedZoneId=Z0123456789ABC \
    CognitoDomainPrefix=vamsdest-dev-auth \
    TavilySecretArn=arn:aws:secretsmanager:us-east-1:123456789012:secret:vamsdest/dev/tavily-api-key-AbCdEf \
    AlertEmail=ops@example.com \
    BedrockModelId=us.anthropic.claude-sonnet-5-5
cd ..

# 4. Publish the web app
scripts/deploy-web.sh vamsdest-dev us-east-1

# 5. Point the apex record at CloudFront: the stack already creates it. Confirm:
aws cloudformation describe-stacks --stack-name vamsdest-dev --query "Stacks[0].Outputs" --output table
```

If `sam deploy` reports "No changes", that is not an error: the stack already matches the template.

---

## 6. Create the first user

Self sign-up is off. Invite users with the CLI or the Cognito console:

```bash
POOL_ID=$(aws cloudformation describe-stacks --stack-name vamsdest-dev \
  --query "Stacks[0].Outputs[?OutputKey=='CognitoUserPoolId'].OutputValue" --output text)

aws cognito-idp admin-create-user --user-pool-id "$POOL_ID" --username traveler@example.com \
  --user-attributes Name=email,Value=traveler@example.com Name=email_verified,Value=true \
  --desired-delivery-mediums EMAIL
```

The user receives a temporary password by email, signs in at `https://<AppDomainName>/`, and sets a new password.

---

## 7. Verify the deployment

Run these after every deploy. Each step has an expected result.

1. **Web app loads.** `curl -sI https://travel.example.com/` returns `200` and includes `strict-transport-security` and `content-security-policy` headers.
2. **Authorizer rejects anonymous calls.** `curl -s -o /dev/null -w "%{http_code}" -X POST https://travel.example.com/api/trips -H "Content-Type: application/json" -d "{}"` returns `401`.
3. **Sign-in works.** In the browser, sign in as the invited user. The page loads without a sign-in loop, and the topbar shows "Sign out".
4. **A trip runs end to end.** Enter a destination and dates and click "Plan my trip". Within a few minutes, each of the four specialists shows a search trail and a completed brief, followed by a coordinated package. The trip reaches "Research complete" or "Some agents need a retry".
5. **Chat answers.** Ask a follow-up question. The answer arrives as an assistant message after one or two polls.
6. **Logs show the harness.** In CloudWatch, the worker log group `/aws/lambda/vamsdest-<env>-worker` shows `agent run finished turns=... searches=... inputTokens=... outputTokens=...` for each agent. The logs must not contain trip text or the Tavily key.
7. **Alarms are quiet.** The DLQ alarm is `OK`, and the API 5xx alarm stays `OK`.

To check the queue and dead-letter queue depth:

```bash
aws sqs get-queue-attributes --queue-url "$(aws cloudformation describe-stacks --stack-name vamsdest-dev \
  --query "Stacks[0].Outputs[?OutputKey=='JobQueueUrl'].OutputValue" --output text)" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
```

---

## 8. How the agentic harness runs on AWS

The agentic harness is `AgentHarness`, a bounded tool-use loop on Bedrock's `Converse` API. The deployment puts it inside the worker Lambda.

**Per trip (worker invocation):**

1. The worker receives one SQS message (batch size 1) containing `{ "type": "trip", "tripId": ... }`.
2. The coordinator starts four specialist agents on a fixed pool of four threads. Each agent is a `harness.run(...)` call with its role, its task, and a search budget (`AGENT_MAX_SEARCHES`, default 4).
3. Inside each agent, Claude either answers or calls `web_search`. The harness runs Tavily, returns numbered results marked as untrusted data, and loops. It stops when the model answers, when the turn limit is reached (`AGENT_MAX_TURNS`, default 8), or when the search budget is spent (the model is then told to answer with what it has).
4. Each search and each status change is written to DynamoDB as an event, so the browser shows progress as it happens.
5. The coordinator merges the four briefs in one more model call with no tools, writes the summary, and sets the run to `completed` or `partial`.

**Per chat question:** the same harness runs with `AGENT_CHAT_MAX_SEARCHES` (default 2) and the existing findings as context.

**Limits that follow from the platform:**

- **15-minute Lambda limit.** A trip normally takes a few minutes. If a run exceeds 900 seconds, Lambda stops it. The message becomes visible again after 960 seconds, and the run starts over and may duplicate events. Keep `AGENT_MAX_SEARCHES` and `AGENT_MAX_TURNS` modest. A deadline inside the harness is a recommended follow-up.
- **At-least-once delivery.** SQS can redeliver a message. A run that already finished is skipped. A run interrupted halfway can repeat some events on retry.
- **Concurrency.** `WORKER_CONCURRENCY` caps simultaneous runs. Extra jobs wait in SQS. This is also the spending cap for Bedrock and Tavily.

**Cost drivers:** Bedrock input and output tokens (each trip is roughly four specialists plus one coordinator call, times the turns used), Tavily searches (up to four per specialist), Lambda GB-seconds (worker runs can be several minutes), and DynamoDB writes (one per event, a few dozen per trip).

### 8.1 Running the deployment through an agent

An AI agent can execute sections 2 to 7 if it follows these rules. The rules are part of the procedure, not suggestions.

**The agent may:**

- Run the verification commands (`mvn -B verify`, `npm run build`, `cfn-lint`).
- Run `scripts/package-lambda.sh` and `sam deploy --no-execute-changeset` to produce and review a change set.
- Read stack outputs and CloudWatch logs and alarms.
- Run the smoke tests in section 7.
- Deploy to **dev** after verification passes, when the user has asked for a deploy.

**The agent must stop and ask a person before:**

- Deploying to **prod**, or any change to the `prod` environment.
- Creating or changing IAM roles or policies, the OIDC provider, or the deploy role.
- Creating a secret or writing any secret value. The agent must not read, print, or log secret values, and must not put them in files, variables, or command lines.
- Deleting a stack, a table, a bucket, a user pool, or a certificate, or changing a `DeletionPolicy`.
- Changing DNS records or the certificate for a domain that serves live traffic.
- Enabling WAF or changing its rules.

**Gates the agent checks before proceeding:**

| Gate | Command | Proceed only if |
|---|---|---|
| Tests | `mvn -B verify` | Exit 0 and all test classes report zero failures |
| Frontend | `npm run build` | Exit 0 |
| Template | `cfn-lint infra/template.yaml` | Exit 0 |
| Package | `scripts/package-lambda.sh` | Prints the file count; both handler classes exist |
| Change set | `sam deploy --no-execute-changeset ...` | Change set lists only expected resources; no replacement of `RunTable`, `FrontendBucket`, or `TripUserPool` |
| Smoke test | Section 7, steps 1 and 2 | `200` on the app; `401` on anonymous API calls |

If any gate fails, the agent reports the exact command and output, makes no further changes, and asks for direction. It does not weaken a test or disable a check to get past a gate.

**Report format.** At the end of a run, the agent reports the stack name, the commit deployed, each gate with pass or fail, the URLs, and anything skipped or unverified.

---

## 9. First-deployment validation checklist

Do this for each new AWS account, and after any change to the template or the IAM policies:

- [ ] Change set reviewed (`--no-execute-changeset`); no unexpected replacements
- [ ] Section 7 checks 1 to 7 pass
- [ ] One complete trip in dev; `partial` status handled correctly if one specialist is forced to fail (for example by an invalid Tavily key in a test secret)
- [ ] DLQ receives a message after three failed deliveries (test with a malformed message, then remove it)
- [ ] Logs contain no trip text, no tokens, and no Tavily key
- [ ] Bedrock invoke permissions tightened to the exact model ARNs after the first run (the template uses wildcards; see section 11)
- [ ] CloudTrail shows no denied calls from the deploy role

---

## 10. Operations

**Rollback.** Check out the previous good commit and redeploy through the pipeline (or run section 5 from that commit). For a web-only issue, rerun `scripts/deploy-web.sh` from the previous build. CloudFormation rolls back failed updates automatically.

**Redrive dead-letter jobs.** After fixing the cause, move messages back to the main queue from the console (SQS > dead-letter queue > Start DLQ redrive), or with `aws sqs start-message-move-task`.

**Retention.** Runs and events expire after `RunRetentionHours` (default 24). DynamoDB point-in-time recovery is on for backups. Logs keep 30 days.

**Scaling.** The API and worker scale automatically. Raise `WORKER_CONCURRENCY` deliberately: each extra slot can run up to four parallel model calls and Tavily searches at once.

**Teardown.** `aws cloudformation delete-stack` removes most resources. The table, the web bucket, and the user pool have retain policies (production also has deletion protection on the table and user pool). Delete those by hand only after exporting data you need, and only with explicit approval.

### Troubleshooting

| Symptom | Likely cause | Check |
|---|---|---|
| Browser stuck on "Signing you in…" | Callback URL or Cognito domain mismatch | `CallbackURLs` equals `https://<AppDomainName>/`; `VITE_COGNITO_DOMAIN` equals the `CognitoDomain` output |
| `401` on every API call while signed in | Token audience mismatch | The issuer and audience in `TripHttpApi` must use the same user pool and client as the web build |
| Trip stays "Joining the research queue" | Worker not triggered or failing | SQS queue depth; worker log group for stack traces |
| Agents show "could not complete" | Tavily or Bedrock access | Worker logs; Tavily key valid; Bedrock model access granted in the region; IAM policy resource ARNs match `BEDROCK_MODEL_ID` |
| Coordinator reports partial | Summary call failed | Worker logs for `coordinator failed`; Bedrock throttling |
| DLQ alarm fires | Repeated worker failures for one message | Read the message body in the DLQ; fix the cause; redrive |
| `sam deploy` fails on the distribution | Certificate not in us-east-1 or not validated | `aws acm describe-certificate --region us-east-1` |
| `sam deploy` fails with "already exists" for the Cognito domain | Prefix taken globally | Choose another `COGNITO_DOMAIN_PREFIX` |

---

## 11. Known limitations

- **Session length.** The ID token lasts one hour, and the web app does not refresh it. Users sign in again after an hour. Adding refresh-token handling is a small follow-up.
- **Cold starts.** The API and worker packages are about 48 MB. The first request after an idle period is slower. SnapStart can be enabled for the API with an alias, but it was not configured in this template.
- **Single region.** There is no cross-region failover. The stack runs in one region.
- **Bedrock IAM scope.** The model permission uses a wildcard on the foundation-model ARN because the inference profile routes across regions. Tighten it after you confirm the regions in use.
- **No booking or purchase.** The agents research and recommend. Nothing books, buys, or changes a reservation.
- **Research quality.** Prices, availability, and weather come from web search results and may be stale or wrong. The UI labels them as leads. Verify with providers before acting.
- **Legacy paths.** The EKS manifests in `deploy/k8s` and the container image still describe the earlier single-process design. They are not used by the Lambda deployment.

## 12. Files involved

| Path | Role |
|---|---|
| `infra/template.yaml` | SAM template: all AWS resources |
| `infra/samconfig.toml` | Non-secret deploy defaults per environment |
| `scripts/package-lambda.sh` | Builds and unpacks the Lambda code |
| `scripts/deploy-web.sh` | Builds and publishes the web app |
| `.github/workflows/deploy.yml` | Verify, deploy, smoke test |
| `src/main/java/com/vamsdest/lambda/` | Lambda entry points (`ApiHandler`, `WorkerHandler`) |
| `src/main/java/com/vamsdest/agent/` | Harness, coordinator, run store, job processor |
| `src/auth.ts`, `src/api.ts` | Cognito PKCE sign-in and bearer-token API client |
