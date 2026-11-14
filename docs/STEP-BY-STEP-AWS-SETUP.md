# VamsDest on AWS: Step-by-Step Guide (Plain English)

This guide takes you from "I have the code on my computer" to "I can open the travel planner in a web browser at my own address."

It is written for someone who is comfortable clicking around but may never have used AWS before. Every step says **what you are doing**, **why**, and **how you know it worked**.

Use the example domain `vasolai.com` throughout (it comes from the repo's notes). Wherever you see `travel.vasolai.com`, use whatever address you want the app to live at.

---

## Part 0: What you are about to build (the big picture)

Think of VamsDest as a small office with several workers:

| Piece | Plain-English job | AWS service |
|---|---|---|
| The website | The page you see and click | **S3** (storage) + **CloudFront** (fast, secure delivery) |
| The front door | Checks that you are logged in | **Cognito** (user accounts and sign-in) |
| The receptionist | Takes your trip request and hands it to the back office | **API Gateway** + the **API Lambda** |
| The to-do list | Holds requests until a worker is free | **SQS** (queue) |
| The research workers | Four AI agents that search the web and write reports | **Worker Lambda** calling **Bedrock** (Claude AI) and **Tavily** (web search) |
| The filing cabinet | Remembers trips and their progress | **DynamoDB** (database) |
| The safe | Stores the Tavily password/key | **Secrets Manager** |
| The alarm system | Emails you if something breaks | **CloudWatch** + **SNS** |

You do **not** need to know how these work to follow this guide. The `infra/template.yaml` file describes all of them in one document, and one command (`sam deploy`) builds them all.

### Which deployment route to use

The repo contains two routes. **Use the Lambda route in this guide.**

| Route | Where it's described | Status |
|---|---|---|
| **Lambda + CloudFront** (this guide) | `docs/AWS-DEPLOYMENT.md`, `infra/template.yaml` | The route the repo is built for. Sign-in works end to end. |
| EKS (Kubernetes) | `docs/DEPLOY-EKS-VASOLAI.md`, `deploy/k8s/` | The EKS guide itself says sign-in does not match between browser and server, so creating a trip would fail. Ignore it for now. |

### Important honesty note

The repo's own docs say the stack has **not yet been deployed to a live AWS account**. Treat your first deployment as a test run. Expect to fix a few small things, and do not put real customers or real money on it until you have run through Part 10.

---

## Part 1: Shopping list (what you need before you start)

Tick these off before touching anything.

### 1A. Accounts and subscriptions

- [ ] **An AWS account** you can log into at https://console.aws.amazon.com. It needs a valid credit card. Costs are pay-as-you-go.
- [ ] **A domain name** (for example `vasolai.com`) that you own.
- [ ] **That domain's DNS in Amazon Route 53.** The stack creates the web address records inside this "hosted zone." If your domain is registered somewhere else (GoDaddy, Namecheap, etc.), you can keep it there, but you must point its nameservers to Route 53 first. Step 4 shows how to check.
- [ ] **A Tavily account** (the web-search service the AI agents use). Sign up at https://tavily.com and copy your API key. Keep it private.
- [ ] **A GitHub account is optional.** You only need it if you later want the automatic deploy pipeline. This guide does not require it.

### 1B. Software to install on your Windows PC

| Install | Get it from | Check it worked (in Git Bash) |
|---|---|---|
| **Git for Windows** (includes **Git Bash**, the terminal the scripts need) | https://git-scm.com | `git --version` |
| **Java 21** (Eclipse Temurin 21 is a good free choice) | https://adoptium.net | `java -version` shows `21` |
| **Apache Maven 3.9+** | https://maven.apache.org | `mvn -version` |
| **Node.js 22 LTS** | https://nodejs.org | `node -v` shows `v22...` |
| **AWS CLI v2** (Windows installer) | https://aws.amazon.com/cli/ | `aws --version` |
| **AWS SAM CLI** (Windows installer) | https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html | `sam --version` |
| **Python 3.10+** (only for the template checker) | https://www.python.org | `python --version` |
| **cfn-lint** (template checker) | run `pip install cfn-lint` | `cfn-lint --version` |

Notes:

- This machine already has Maven, Node, Java, and Git Bash. It has **Java 25**, but the project is built for **Java 21**. Install Java 21 and make sure it is the one used by `java -version`, or the build may behave differently from the one the authors tested.
- It does **not** yet have AWS CLI, SAM CLI, or cfn-lint. Install those.
- The `.sh` scripts need **Git Bash**, not PowerShell. The `package-lambda.sh` script also needs the `unzip` command. Git Bash usually includes it. If you see `unzip: command not found`, install it or use WSL.
- Restart your terminal after installing anything so the new commands are found.

---

## Part 2: Lock down your AWS account (5 minutes, do not skip)

Your AWS root login (the email and password you used to sign up) is all-powerful. Use it only for account setup.

1. **Turn on multi-factor authentication (MFA) for the root user.** In the AWS console, go to your account name (top right) > **Security credentials** > **Multi-factor authentication** > **Assign MFA device**. Use an authenticator app.
2. **Create a day-to-day admin user.** Go to **IAM Identity Center** (search for it in the console) and enable it if prompted. Create a user with your email, and give it the **AdministratorAccess** permission set. This is what you will use for everything from now on.
   - If that feels like too much, an IAM user with `AdministratorAccess` also works for a first test. Create an access key only if you are comfortable storing it on your own PC.
3. **Set a budget alarm.** Search for **Billing and Cost Management** > **Budgets** > **Create budget** > **Monthly cost budget**. Set a limit you are comfortable with (for example $50) and add your email. You will get a warning before a surprise bill.

**How you know it worked:** you can sign in with the admin user, and the root user has MFA turned on.

---

## Part 3: Connect your computer to AWS

1. Open **Git Bash** (search "Git Bash" in Windows Start).
2. Run:

   ```bash
   aws configure sso
   ```

   Answer the questions (start URL and region come from IAM Identity Center; pick a profile name such as `vamsdest`). A browser window opens: sign in and approve.

   If you used an IAM user with access keys instead, run `aws configure` and paste the Access Key ID and Secret when asked. **Never paste keys into a chat, email, or the repo.**

3. Confirm you are connected to the right account:

   ```bash
   aws sts get-caller-identity
   ```

   You should see your account number and user name. Write down the account number.

4. Choose your working region. **Use `us-east-1` (N. Virginia) for this guide.** It is the simplest choice because the CloudFront certificate and the optional WAF must live there anyway.

   ```bash
   export AWS_REGION=us-east-1
   export AWS_DEFAULT_REGION=us-east-1
   ```

   These two lines last only for that Git Bash window. Run them again in any new window.

**How you know it worked:** `aws sts get-caller-identity` prints your account and user without an error.

---

## Part 4: Check your domain is ready

1. Confirm your hosted zone exists in Route 53:

   ```bash
   aws route53 list-hosted-zones-by-name --dns-name vasolai.com
   ```

   Look for `"Name": "vasolai.com."` in the output and copy its `Id`. It looks like `/hostedzone/Z0123456789ABC`. The part after `/hostedzone/` (starting with `Z`) is your **HOSTED_ZONE_ID**.

2. If nothing comes back, create the zone: **Route 53 > Hosted zones > Create hosted zone**, name `vasolai.com`, type Public. Route 53 then shows four nameservers. At your domain registrar, replace the nameservers with those four. This can take up to 48 hours, but usually much less. Wait for it before continuing.

**How you know it worked:** the `list-hosted-zones-by-name` command shows your domain.

---

## Part 5: Get the HTTPS certificate

The site must use HTTPS (the padlock). AWS issues the certificate for free through **ACM**.

1. In the AWS console, switch the region selector (top right) to **US East (N. Virginia) us-east-1**. This is required for certificates used by CloudFront.
2. Open **Certificate Manager (ACM)** > **Request a certificate** > **Request a public certificate**.
3. Domain name: `travel.vasolai.com` (or your chosen address). Validation method: **DNS validation**. Click **Request**.
4. Open the new certificate. Under **Domains**, click **Create records in Route 53**, then confirm. This adds the proof records automatically.
5. Wait 5 to 30 minutes until **Status** shows **Issued**. You can also check from Git Bash:

   ```bash
   aws acm list-certificates --region us-east-1 --query "CertificateSummaryList[?DomainName=='travel.vasolai.com'].[CertificateArn,Status]" --output table
   ```

6. Copy the **Certificate ARN** (it starts with `arn:aws:acm:us-east-1:`). You will need it as **CERTIFICATE_ARN**.

**How you know it worked:** the status is `ISSUED`.

---

## Part 6: Turn on the AI model (Claude on Bedrock)

AWS does not give every account access to every AI model automatically.

1. In the console, switch to **us-east-1** and open **Amazon Bedrock**.
2. Go to **Model access** (or the equivalent "Model catalog" page in the current console) and request access to the Anthropic Claude model you want.
3. The default model in this project is **`us.anthropic.claude-sonnet-5-5`**. If you cannot find it, choose the closest Claude model your account offers, and use its ID in Part 9 as **BEDROCK_MODEL_ID**. Only change this if the default is unavailable.
4. Wait until access shows **Access granted**. Approval is often instant but can take a few minutes.
5. Optional: a Bedrock Guardrail (content filter) can be added later. Leave **BEDROCK_GUARDRAIL_ID** and **BEDROCK_GUARDRAIL_VERSION** empty for a first test.

**How you know it worked:** the model shows **Access granted** in the Bedrock console.

---

## Part 7: Store your Tavily key safely

The Tavily key goes into **Secrets Manager**, a locked box. It is never written into the code or the repo.

1. Console: **Secrets Manager** > **Store a new secret**.
2. Secret type: **Other type of secret**. Choose the **Plaintext** tab. Delete any default text, then paste **only your Tavily key** (no quotes, no JSON, nothing else).
3. Click **Next**. Secret name: `vamsdest/dev/tavily-api-key`. Keep the other defaults. Click **Next**, then **Store**.
4. Open the secret and copy its **ARN** (it starts with `arn:aws:secretsmanager:`). You will need it as **TAVILY_SECRET_ARN**.

**How you know it worked:** the secret appears in the list with the name above.

The command-line alternative is in `docs/AWS-DEPLOYMENT.md`, section 3.2. Use the console if you are unsure.

---

## Part 8: Write down your settings (the "worksheet")

Fill in this table once. Every value is used in Part 10. None of these values is a password, so it is fine to keep them in a notes file.

| Setting name | What it is | Example value (replace with yours) |
|---|---|---|
| **AppDomainName** | The address people will visit | `travel.vasolai.com` |
| **CertificateArn** | From Part 5 | `arn:aws:acm:us-east-1:123456789012:certificate/abcd-...` |
| **HostedZoneId** | From Part 4, starts with `Z` | `Z0123456789ABC` |
| **TavilySecretArn** | From Part 7 | `arn:aws:secretsmanager:us-east-1:123456789012:secret:vamsdest/dev/tavily-api-key-AbCdEf` |
| **AlertEmail** | Where problem alarms are emailed (optional) | `you@example.com` (or leave empty) |
| **BedrockModelId** | From Part 6 | `us.anthropic.claude-sonnet-5-5` |
| **WorkerConcurrency** | Max trips researched at once (controls spending) | `5` |
| **EnableWaf** | Extra firewall for the website. It costs extra. | `false` |

Notes on the worksheet:

- **Keep `EnableWaf` as `false`** for a first test. It adds a monthly charge. The template is designed so it can be turned on later.
- **Sign-in uses phone numbers, not a Cognito domain.** Users are added with `scripts/auth-admin.sh add-user <+E164 number>`. SMS needs production access in SNS (see `docs/AWS-DEPLOYMENT.md`, section 6).
- **Do not put your Tavily key or any access keys in this worksheet.** Only ARNs, names, and IDs.

---

## Part 9: Build and check the code on your computer

Do this in **Git Bash**, from the project folder:

```bash
cd /c/Users/newvd/Downloads/Agents
```

Run each command in order. Stop at the first error and read it.

1. **Run the backend tests** (takes a few minutes the first time):

   ```bash
   mvn -B verify
   ```

   **Good:** the last lines say `BUILD SUCCESS`.

2. **Install and build the website:**

   ```bash
   npm ci
   npm run build
   ```

   **Good:** it ends without errors and creates a `dist` folder.

3. **Check the infrastructure template:**

   ```bash
   cfn-lint infra/template.yaml
   ```

   **Good:** no output at all. Any message means something needs fixing.

4. **Package the backend for AWS:**

   ```bash
   scripts/package-lambda.sh
   ```

   **Good:** it prints `Lambda code ready in build/lambda (...files)`.

**How you know it worked:** all four commands succeeded. If a test fails, do not skip it. Copy the error and ask for help.

---

## Part 10: Deploy to AWS (the main step)

This creates all the AWS pieces listed in Part 0. It takes about **15 to 25 minutes**, mostly waiting for CloudFront.

### 10A. Preview first (recommended)

This shows what will be created without creating anything yet.

```bash
cd /c/Users/newvd/Downloads/Agents/infra

sam deploy --config-env dev --region us-east-1 --no-execute-changeset \
  --parameter-overrides \
    AppDomainName=travel.vasolai.com \
    CertificateArn=arn:aws:acm:us-east-1:123456789012:certificate/REPLACE-ME \
    HostedZoneId=Z0123456789ABC \
    TavilySecretArn=arn:aws:secretsmanager:us-east-1:123456789012:secret:vamsdest/dev/tavily-api-key-REPLACE \
    AlertEmail=you@example.com \
    BedrockModelId=us.anthropic.claude-sonnet-5-5
```

**Good:** it says a change set was created and lists resources to be **added**. On a first run, nothing should say "replace" or "delete."

Replace every example value with the numbers from your worksheet (Part 8). Keep the line breaks with a backslash `\` at the end of each line, as shown.

### 10B. Deploy for real

Run the same command, but without `--no-execute-changeset`:

```bash
cd /c/Users/newvd/Downloads/Agents/infra

sam deploy --config-env dev --region us-east-1 \
  --parameter-overrides \
    AppDomainName=travel.vasolai.com \
    CertificateArn=arn:aws:acm:us-east-1:123456789012:certificate/REPLACE-ME \
    HostedZoneId=Z0123456789ABC \
    TavilySecretArn=arn:aws:secretsmanager:us-east-1:123456789012:secret:vamsdest/dev/tavily-api-key-REPLACE \
    AlertEmail=you@example.com \
    BedrockModelId=us.anthropic.claude-sonnet-5-5
```

It will ask **"Deploy this changeset? [y/N]"**. Type `y` and press Enter.

Then go back to the project root:

```bash
cd /c/Users/newvd/Downloads/Agents
```

**Good:** it ends with `Successfully created/updated stack - vamsdest-dev`. If you see `No changes to deploy`, that is fine.

**If it fails:** the message usually names the problem. Look at the **Troubleshooting** section (Part 12). Failed deployments roll back automatically, so it is safe to fix and run again.

### 10C. Upload the website

Now the website files go into the storage bucket:

```bash
scripts/deploy-web.sh vamsdest-dev us-east-1
```

**Good:** it ends with `Web app published to bucket ... and invalidated distribution ...`.

### 10D. Check the stack's outputs

```bash
aws cloudformation describe-stacks --stack-name vamsdest-dev \
  --query "Stacks[0].Outputs" --output table
```

Note the **CognitoUserPoolId** value. You need it in Part 11.

---

## Part 11: Create your first user

Nobody can sign up by themselves. You invite each person.

Replace the email in this command with the real email address of the person who will use the app (yours, for a first test):

```bash
POOL_ID=$(aws cloudformation describe-stacks --stack-name vamsdest-dev \
  --query "Stacks[0].Outputs[?OutputKey=='CognitoUserPoolId'].OutputValue" --output text)

aws cognito-idp admin-create-user --user-pool-id "$POOL_ID" --username you@example.com \
  --user-attributes Name=email,Value=you@example.com Name=email_verified,Value=true \
  --desired-delivery-mediums EMAIL
```

The user gets an email with a **temporary password**. Then:

1. Open `https://travel.vasolai.com/` in your browser.
2. Sign in with the email and temporary password.
3. Set a new password when asked (at least 12 characters, with upper and lower case, numbers, and symbols).

**Good:** the planner page loads and the top bar shows **Sign out**.

---

## Part 12: Test that everything works

Work through these in order. Each one has a "good" result.

| # | What to do | Good result |
|---|---|---|
| 1 | Open `https://travel.vasolai.com/` in a private/incognito window | The page loads with the padlock icon |
| 2 | Open that address in a new browser with no login | You are asked to sign in (not an error page) |
| 3 | Sign in as your user | Page loads, no sign-in loop, "Sign out" visible |
| 4 | Enter a destination and dates, click **Plan my trip** | Within a few minutes, four specialist sections show search steps and briefs, then a combined plan. Status says "Research complete" or "Some agents need a retry." |
| 5 | Ask a follow-up question in the chat box | An answer appears after a short wait |
| 6 | Open **CloudWatch > Log groups > `/aws/lambda/vamsdest-dev-worker`** | Log lines show `agent run finished`. The logs must **not** show your trip text or your Tavily key. |
| 7 | Open **CloudWatch > Alarms** | The alarms show **OK** (green) |

**If step 4 says "could not complete"**, the most likely causes are: the Tavily key is wrong, or Bedrock access is not yet granted in us-east-1. Check the worker log for the exact error.

---

## Part 13: Common problems and fixes

| What you see | What it usually means | What to do |
|---|---|---|
| `unzip: command not found` | Git Bash is missing `unzip` | Install it, or run the commands in WSL |
| SMS code never arrives | SNS sandbox or wrong number format | Request production SMS access in SNS; use `+<country><number>` format |
| Certificate error during deploy | Certificate is not in us-east-1, or not yet `ISSUED` | Repeat Part 5; wait for `ISSUED` |
| "Parameter ... failed to satisfy constraint" | A value is empty or in the wrong format | Check the worksheet; `WorkerConcurrency` must be a number |
| Sign-in page loops or shows a blank page | Callback address does not match | Make sure `AppDomainName` matches the certificate exactly |
| `401` on every request while signed in | Token mismatch | Redeploy the stack and the website (Part 10B and 10C) |
| Trip stuck on "Joining the research queue" | The worker is not running or is failing | Check the worker log group for errors |
| Deploy says the stack is busy or in progress | An earlier deploy is still running | Open **CloudFormation > Stacks** in the console, wait until the status is stable (for example `CREATE_COMPLETE` or `ROLLBACK_COMPLETE`), then retry |
| Bills higher than expected | Normal usage costs money (AI calls, searches, traffic) | See Part 14 |

---

## Part 14: Costs and keeping it safe

**Where the money goes (pay-as-you-go):**

- **Bedrock (Claude AI):** charged per token of text. Usually the biggest cost. Each trip uses several AI calls.
- **Tavily:** charged per search, under your Tavily plan.
- **CloudFront, Lambda, API Gateway, DynamoDB, SQS:** small amounts per request. Usually cheap at low use.
- **WAF:** only if you set `EnableWaf=true`. It adds a monthly base charge.
- **Route 53 hosted zone and certificate:** a small monthly fee for the zone; the certificate is free.

**Keep it cheap while testing:**

- Keep `WorkerConcurrency` at `5` or lower.
- Keep `EnableWaf` as `false`.
- Keep the budget alarm from Part 2 active.
- Check **Billing and Cost Management** once a week during testing.

---

## Part 15: Going live later (optional, after testing)

When you are happy with the dev version:

1. **Repeat the steps with `prod`:** use a new domain or subdomain (for example `app.vasolai.com`), new certificate, new Tavily secret (`vamsdest/prod/tavily-api-key`), and `--config-env prod` with stack name `vamsdest-prod`. The prod stack uses extra protection (deletion protection on the database and user pool).
2. **Consider enabling WAF** (`EnableWaf=true`) for prod. This requires the us-east-1 region, which this guide already uses.
3. **Set up the automatic deploy pipeline** (GitHub Actions) if you want deploys to happen from pushes to `main`. Setup is in `docs/AWS-DEPLOYMENT.md`, sections 3.4 and 3.5. It is more work than the manual steps, so do it after the manual deploy has worked.
4. **Run the validation checklist** in `docs/AWS-DEPLOYMENT.md`, section 9, before inviting real users.

---

## Part 16: Shutting it down (to stop charges)

To remove the app:

```bash
aws cloudformation delete-stack --stack-name vamsdest-dev --region us-east-1
```

This removes most of the pieces. It deliberately **keeps** the database (DynamoDB table), the website bucket, and the user pool, so no data is lost by accident. Delete those by hand only if you are sure:

- DynamoDB table: `vamsdest-dev-runs`
- S3 bucket: starts with `vamsdest-dev-web-`
- Cognito user pool: `vamsdest-dev-users`

The Tavily secret, the certificate, and the Route 53 hosted zone also stay. Delete them yourself if you no longer need them.

**Do not delete anything without deciding that you no longer need the data.**

---

## Appendix A: What the AI assistant needs from you

If you want the assistant (Claude Code) to carry out the deployment with you, it needs these from you. **Do not send secret keys, passwords, or access keys in chat.**

| Needed | Why | Send as |
|---|---|---|
| Your domain name and the address you want (for example `travel.vasolai.com`) | Website address and certificate | Plain text |
| Confirmation that the domain's hosted zone is in Route 53 | Records are created there | Yes/no, or the zone ID (`Z...`) |
| The certificate ARN (after Part 5) | HTTPS | ARN text |
| The Tavily secret ARN (after Part 7) | The worker reads the key from there | ARN text. **Not the key itself.** |
| Your AWS region, and whether `us-east-1` is fine | Region rules | Yes/no |
| The Bedrock model ID you have access to | AI model | Model ID text |
| Your alert email (or "none") | Alarm emails | Plain text |
| A unique Cognito prefix | Sign-in address | Plain text |
| Whether to turn on WAF | Cost and protection | Yes/no |
| Whether this is a test (`dev`) or live (`prod`) | Which stack to create | `dev` or `prod` |
| Your go-ahead to run `sam deploy` for real | It creates billable resources | Explicit "yes, deploy" |

The assistant will also ask before any of the following, because they are hard to undo or affect security: creating or changing IAM roles and policies, writing a secret value, deleting anything, changing DNS for a live domain, or deploying to `prod`.

---

## Appendix B: Plain-English glossary

- **AWS:** Amazon's cloud. You rent computers, storage, and services by the hour or per use.
- **Region:** a physical location of AWS data centers. This guide uses `us-east-1`.
- **IAM:** AWS's permission system. It decides who and what may do what.
- **Lambda:** a piece of code that runs only when asked. You pay only while it runs.
- **Stack (CloudFormation):** a group of AWS resources created together from one template file. `vamsdest-dev` is the stack for the dev version.
- **SAM:** a helper tool that builds and deploys Lambda-based stacks from `infra/template.yaml`.
- **ARN:** a long unique ID for an AWS item, always starting with `arn:aws:`.
- **Route 53:** AWS's DNS service. It turns `travel.vasolai.com` into the right server address.
- **ACM:** AWS's certificate service. Gives you the padlock (HTTPS).
- **CloudFront:** AWS's global website delivery network.
- **Cognito:** AWS's user sign-in service.
- **Bedrock:** AWS's service for using AI models such as Claude.
- **Tavily:** a web-search service the AI agents use to look things up.
- **Secrets Manager:** a locked box for passwords and API keys.
- **DynamoDB:** a database service.
- **SQS:** a queue. It holds work until a worker picks it up.
- **CloudWatch:** AWS's logs and alarms.
- **Git Bash:** a Linux-style terminal for Windows. The project's scripts need it.
