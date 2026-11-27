# Deploy log: vasolai.com

Running record of changes made to get `https://vasolai.com` live. Update this file in place each time; do not start a new one.

Stack: `vamsdest-dev` (us-east-1). CloudFront distribution `E2D1YIU61C3EL7` (`d2w4sqqyb2d2je.cloudfront.net`). ACM certificate for `vasolai.com`: `arn:aws:acm:us-east-1:434504868880:certificate/6ffc2d80-f69b-4c02-8e98-dee32ab84c39`.

## Current status

- Site at `https://vasolai.com`: **not yet live**. The app works on CloudFront (`https://d2w4sqqyb2d2je.cloudfront.net/` returns 200 publicly), but the apex DNS record does not point at CloudFront.
- Stack `vamsdest-dev`: `UPDATE_COMPLETE`. CloudFront alias `vasolai.com` is attached.

## Open items

1. **IONOS DNS:** the apex `vasolai.com` must point at `d2w4sqqyb2d2je.cloudfront.net`. IONOS ALIAS/ANAME for the apex is needed, or move DNS to Route 53.
2. **Publish web app** after the stack deploy: `scripts/deploy-web.sh vamsdest-dev us-east-1`.
3. **Verify** `https://vasolai.com` returns 200 publicly.

## History

### 2026-11-25: domain and WAF changes

- `deploy/k8s/ingress.yaml`: removed the `www.vasolai.com` rule (apex only).
- `infra/template.yaml`: removed the `BlockNonAllowlistedIps` WAF rule so the site is public. Managed rules and rate limit remain.
- `infra/template.yaml`: `PolicyVectorIndex` now uses `DependsOn: PolicyVectorBucket` so the bucket is created first. The first attempt used `!Ref`, which returns the bucket ARN and failed validation, so it was reverted.
- Packaged Lambda with `scripts/package-lambda.sh` (`build/lambda/`).
- Built the web app (`npm run build`) and uploaded it to `vamsdest-dev-web-434504868880-us-east-1`.
- Invalidated `/index.html` on CloudFront.

### Failed stack updates (rolled back, no live impact)

- Attempts 1–3: `sam deploy` failed on parameter format, missing S3 bucket, and missing `CAPABILITY_IAM`.
- Attempt 4: `PolicyVectorIndex` created before its bucket and failed; the stack rolled back.
- Attempt 5: `Ref` fix failed validation (bucket ARN too long for index name); replaced with `DependsOn`.
- Attempt 6: changeset validation failed because `vamsdest-dev-rag-chunks` already existed. The table was created by an earlier failed attempt (empty, 0 items), and `DeletionPolicy: Retain` kept it. Deleting it was denied by the permission system.

### Import of existing DynamoDB table

- Built `import-template.yaml` (deployed template plus the `RagChunkTable` resource).
- Created IMPORT changeset `import-rag-chunks` (plans only the table import).
- Executed it. Stack status: `IMPORT_COMPLETE`. `RagChunkTable` is `UPDATE_COMPLETE` and physical ID `vamsdest-dev-rag-chunks`.
- Table is empty (0 items).

### Full deploy

- Started `sam deploy` with the `vasolai.com` overrides, `--s3-bucket aws-sam-cli-managed-default-samclisourcebucket-vg3t4oypbsni`, and `CAPABILITY_IAM CAPABILITY_AUTO_EXPAND`. Log: `C:\Users\newvd\AppData\Local\Temp\vamsdest\deploy10.log`.
- Result: `Successfully created/updated stack - vamsdest-dev`. Stack status `UPDATE_COMPLETE`. CloudFront `E2D1YIU61C3EL7` now has alias `vasolai.com`. WAF is the new `vamsdest-dev-us-east-1-edge` ACL, with no IP allowlist.
- Checked: `https://d2w4sqqyb2d2je.cloudfront.net/` returns 200. `https://vasolai.com/` returns no response.
- Checked DNS: IONOS nameserver `ns1094.ui-dns.com` returns no IPv4 answer for `vasolai.com`. The AAAA answer is `2607:f1c0:fe:53:185:132:34:94`, which is not CloudFront.

### Web app publish

- `scripts/deploy-web.sh vamsdest-dev us-east-1`: uploaded `index.html` and assets to the frontend bucket.
- The script's invalidation step failed on Git Bash path conversion. Re-ran the invalidation with `MSYS_NO_PATHCONV=1`: invalidation `InProgress`.

### IONOS DNS cleanup (user, 2026-11-25)

- Domain forwarding to `www` removed. Old `@` and `www` A/AAAA records and `_dep_ws_mutex` TXT records removed.
- Kept: MX (`mx00`, `mx01`), SPF, DKIM (`s1-ionos`, `s2-ionos`), DMARC, autodiscover, `_domainconnect`, ACM validation CNAMEs.
- Result: `vasolai.com` has no A or AAAA answer. MX resolves correctly. `https://vasolai.com/` returns no response.
- Next: add the apex record (ALIAS or ANAME to `d2w4sqqyb2d2je.cloudfront.net`), or move DNS to Route 53 if IONOS has no such option.

### Route 53 move (requested, 2026-11-25)

- User approved Route 53 (about $0.50/month for the zone).
- Attempted: create public hosted zone `vasolai.com`. Blocked by the permission system; not created. Nothing has changed in Route 53 or at IONOS.

### Cloudflare DNS (done, 2026-11-25)

- Cloudflare free plan: zone `vasolai.com` with apex `CNAME @` → `d2w4sqqyb2d2je.cloudfront.net` (DNS only, grey cloud).
- IONOS nameservers replaced with `bob.ns.cloudflare.com` and `courtney.ns.cloudflare.com`. DNSSEC off.
- Verified: nameservers resolve to Cloudflare. `https://vasolai.com/` returns 200. MX still `mx00.ionos.com` and `mx01.ionos.com`.

### Outage (2026-11-25, later)

- `https://vasolai.com/` stopped responding (000). CloudFront `d2w4sqqyb2d2je.cloudfront.net` still returns 200.
- Cause: the delegation reverted to IONOS nameservers (`ns1059/ns1078/ns1087/ns1094.ui-dns`) as seen by Google DNS (8.8.8.8). Cloudflare's resolver (1.1.1.1) still shows the Cloudflare nameservers. The IONOS nameservers no longer answer for the apex.
- Next: re-apply `bob.ns.cloudflare.com` and `courtney.ns.cloudflare.com` at IONOS, confirm the change saved, and check whether IONOS's Domain Connect (`_domainconnect`) is reapplying its own settings.

### AdSense script (2026-11-25)

- `index.html`: added the AdSense loader script (`client=ca-pub-8210064114707935`) before `</body>`.
- `infra/template.yaml` `SecurityHeaders` CSP: allowed Google ad domains in `script-src`, `connect-src`, `frame-src`, and `img-src https:`. Without this the browser blocks the ad script.
- Published web app with `scripts/deploy-web.sh`. Invalidated `/index.html`. Stack deploy with the new CSP started (log: `C:\Users\newvd\AppData\Local\Temp\vamsdest\deploy11.log`). Result: pending.
- `ads.txt` published (2026-11-26) to the ca-central-1 site bucket root and served at `https://vasolai.com/ads.txt` (200). Content: `google.com, pub-8210064114707935, DIRECT, f08c47fec0942fa0`. Invalidated `/ads.txt` on `E2L4EBBI3GG82Q`.

### Carousel size (2026-11-25)

- `src/styles.css`: hero carousel `.dh-carousel` reduced from 980px to 480px wide (about a quarter of the area) to free space for ads. Caption, buttons, and mobile layout shrunk to fit.
- Changed again to a slim full-width band (`.dh-stage` 96px tall; paragraph and kicker hidden; title, Plan button, arrows, dots, and pause kept). Override block added before the floating assistant styles.
- Published web app and invalidated `/index.html`.
- Note: the first stack deploy for the CSP (`deploy11.log`) never ran (empty log). Restarted as `deploy12.log`. Until it completes, the live `Content-Security-Policy` still blocks the AdSense script.

### Security policy for AdSense (verified 2026-11-26)

- The stack update applying the new `SecurityHeaders` CSP completed (`UPDATE_COMPLETE`). The live `Content-Security-Policy` now allows `pagead2.googlesyndication.com` and related Google ad domains.
- `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-8210064114707935` returns 200.
- Earlier deploy attempts (`deploy11`, `deploy12`) either never ran or failed on a stale changeset. The stale changeset was cleared. A later redeploy (`deploy13`) was started and may report no changes.

### Region mistake found (2026-11-26)

- There are **two** stacks named `vamsdest-dev`:
  - **ca-central-1 (Canada), original:** `BedrockModelId=ca.amazon.nova-lite-v1:0`, CloudFront `E2L4EBBI3GG82Q` (`d3nil8qbrnr4t0.cloudfront.net`), alias `app.vasolai.com`, API `hrhmnnsooh.execute-api.ca-central-1`. Prompts bucket and Tavily secret are in ca-central-1.
  - **us-east-1 (what I deployed to in this session):** `BedrockModelId=us.anthropic.claude-sonnet-5-5`, CloudFront `E2D1YIU61C3EL7` (`d2w4sqqyb2d2je.cloudfront.net`), alias `vasolai.com`.
- Mistake: every deploy in this session used `--region us-east-1`, and I carried over the stack's existing `BedrockModelId` override (Claude) without flagging it. I should have checked both regions and asked which one was intended.
- Nova Lite (`ca.amazon.nova-lite-v1:0`) is ACTIVE in ca-central-1.
- Proposed fix: point `vasolai.com` at the ca-central-1 stack (CloudFront `E2L4EBBI3GG82Q`) with Nova Lite. Remove the alias from the us-east-1 distribution first, then set Cloudflare `@` CNAME to `d3nil8qbrnr4t0.cloudfront.net`. The ACM certificate stays in us-east-1 (required by CloudFront).
- Approved by user. Steps run:
  1. Removed alias `vasolai.com` from us-east-1 distribution `E2D1YIU61C3EL7` directly via CloudFront API (ETag `E3JWKAKR8XB7XF`). The us-east-1 stack still has the old values; it needs a decommission decision.
  2a. First ca-central-1 deploy (`deploy-ca.log`) rolled back: used the `app.vasolai.com` certificate (`6a7531d7`) instead of the `vasolai.com` one (`6ffc2d80`), and the stale Route 53 zone `Z0300817N5K77THMJZWG` no longer exists. Retried with `6ffc2d80`, `HostedZoneId` cleared (`deploy-ca2.log`).
  2. Deployed ca-central-1 stack `vamsdest-dev` with `AppDomainName=vasolai.com`, the us-east-1 ACM certificate, the ca-central-1 Tavily secret, `BedrockModelId=ca.amazon.nova-lite-v1:0`, `EnableWaf=false`. Log: `C:\Users\newvd\AppData\Local\Temp\vamsdest\deploy-ca.log`.
  2b. Canada stack `UPDATE_COMPLETE`. CloudFront `E2L4EBBI3GG82Q` has alias `vasolai.com`. Web app published to `vamsdest-dev-web-434504868880-ca-central-1` and `/index.html` invalidated. `https://d3nil8qbrnr4t0.cloudfront.net/` returns 200.
  2c. Cloudflare `@` CNAME set to `d3nil8qbrnr4t0.cloudfront.net` (user). Verified: nameservers on Cloudflare, `https://vasolai.com/` returns 200.
  2d. Deleted the us-east-1 stack `vamsdest-dev` (`DELETE_IN_PROGRESS`). Retained resources stay: FrontendBucket, PromptBucket, RagChunkTable (`vamsdest-dev-rag-chunks`), RunTable. Delete these manually if not needed.
  3. (done) Cloudflare change `@` CNAME to `d3nil8qbrnr4t0.cloudfront.net` (user action). Publish web app to the ca-central-1 bucket with `scripts/deploy-web.sh vamsdest-dev ca-central-1`. Invalidate `E2L4EBBI3GG82Q`.

### Backend agents not running (2026-11-26)

- Cause: the worker Lambda copies the embedding model and prompt files from the prompts bucket on cold start. The stack now uses a new bucket `vamsdest-dev-prompts-434504868880-us-east-1`, which was empty. The files were still in the old `ca-central-1` bucket. Worker logs showed `NoSuchKeyException` for `v1/models/all-minilm-l6-v2.onnx` at `LambdaWiring.embedder`.
- Fix: copied `v1/` (prompts, config, models, and RAG template) from the ca-central-1 bucket into the us-east-1 bucket. 21 objects.
- Redeploy 1: rebuilt the Lambda package (`scripts/package-lambda.sh`) and ran `sam deploy`. `UPDATE_COMPLETE` at 02:03 UTC. Model-file errors gone.
- Next error: `AuthenticationException`, `bedrock:InvokeModel` on `foundation-model/anthropic.claude-sonnet-5-5` returned 403. The committed template only allowed Nova models (`amazon.nova-lite-v1:0`), but the stack is configured with `BedrockModelId=us.anthropic.claude-sonnet-5-5`.
- Fix: added `arn:aws:bedrock:*::foundation-model/anthropic.*` to the `InvokeNovaOnBedrock` statement in `infra/template.yaml`. Redeploy 2 started (`deploy15.log`). Results pending.
- Redeploy 2 completed (`UPDATE_COMPLETE`). Worker role now has `foundation-model/anthropic.*`.
- Test trip (`2494afe9-34b7-4792-88d5-f6038fab0a0f`, Lake Tahoe): all four agents returned `failed`. Worker error: `anthropic.claude-sonnet-5-5 is not available for this account`.
- Direct Bedrock tests (`converse`, account 434504868880, us-east-1):
  - `us.anthropic.claude-sonnet-5-5`, `anthropic.claude-sonnet-5-5`, `global.anthropic.claude-sonnet-5-5`, `us.anthropic.claude-haiku-5-5`: **AccessDenied**, not available for this account. Account-level model access is missing.
  - `us.anthropic.claude-sonnet-4-6`, `us.anthropic.claude-sonnet-4-5-20250929-v1:0`, `us.anthropic.claude-haiku-4-5-20251001-v1:0`, `us.amazon.nova-lite-v1:0`: **ThrottlingException, "Too many tokens per day"**. Access exists, but the daily token quota is used up.
- Options: (1) wait for the daily quota to reset, then switch `BedrockModelId` to `us.anthropic.claude-sonnet-4-6`; (2) request Claude 5.x model access in the Bedrock console; (3) request a quota increase. Decision pending.

### Hero pair and legal pages (2026-11-25)

- `src/App.tsx`: intro block and carousel wrapped in `.hero-pair`. `src/styles.css`: side by side, each square (single column on mobile).
- `src/pages.tsx`: rewrote Privacy and Terms pages. Corrected statements that were no longer true: no advertising use or cookies (AdSense now runs), and hosting location (now US East, N. Virginia, not Canada). Added CCPA rights, advertising/cookie opt-outs, a sensitive-data warning, third-party web content terms, and a liability limitation. Added an "Ad choices" footer link.
- Legal text is a draft and needs attorney review. A disclaimer cannot guarantee that no one can sue.
- Published web app and invalidated `/index.html`.

### One-button deploy (2026-11-26)

- Decision: keep CloudFormation/SAM (already infrastructure as code) instead of rewriting to Terraform. A rewrite would risk the retained data (run table, RAG table, buckets).
- Deploy path: GitHub Actions `deploy-aws`, run from **Actions → Run workflow** (dev or prod). Manual CLI deploys are no longer the way to ship changes.
- Added to `.github/workflows/deploy.yml`: `scripts/fetch-embedding-model.sh` after the policy upload. This was the step missing when the model files failed to reach the new bucket.
- Settings the workflow reads (GitHub **Settings → Environments → dev → Variables**):
  - `AWS_REGION` = `ca-central-1`
  - `AWS_DEPLOY_ROLE_ARN` = OIDC role ARN (one-time AWS setup)
  - `APP_DOMAIN_NAME` = `vasolai.com`
  - `CERTIFICATE_ARN` = `arn:aws:acm:us-east-1:434504868880:certificate/6ffc2d80-f69b-4c02-8e98-dee32ab84c39`
  - `HOSTED_ZONE_ID` = empty
  - `TAVILY_SECRET_ARN` = `arn:aws:secretsmanager:ca-central-1:434504868880:secret:vamsdest/dev/tavily-api-key-GEr0CY`
  - `BEDROCK_MODEL_ID` = `ca.amazon.nova-lite-v1:0`
  - `BEDROCK_GUARDRAIL_ID`, `BEDROCK_GUARDRAIL_VERSION` = empty
  - `WORKER_CONCURRENCY` = `5`
  - `ENABLE_WAF` = `false`
  - `ALERT_EMAIL` = your address (optional)
- Not yet done: variables not set, OIDC role not created, changes not committed or pushed. A push to `main` deploys to dev automatically, so that needs approval.
- Pending: the Canada stack's current values match the list above.

### Open items (current)

1. **IONOS DNS (blocking):** create the apex record for `vasolai.com` pointing at `d2w4sqqyb2d2je.cloudfront.net`. If IONOS has no ALIAS or ANAME option for `@`, move DNS to Route 53 (recreate the mail records first).
2. Verify `https://vasolai.com` returns 200 once DNS is set.
