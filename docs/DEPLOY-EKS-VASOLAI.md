# VamsDest on EKS pods, served at vasolai.com

Step-by-step runbook for running VamsDest as a Kubernetes pod on Amazon EKS, behind an AWS Application Load Balancer, with DNS on `vasolai.com` and `www.vasolai.com`.

Run the shell commands in **Git Bash** from the repository root (the `scripts/*.sh` files need it too). Each step says what to check before moving on.

> **Read first: three things that block a working site.**
>
> 1. **Sign-in does not match between the browser and the server.** The pod runs the Spring `prod` profile, which uses a server-side OIDC session (`oauth2Login`). The web build in the `Dockerfile` has `VITE_AUTH_REQUIRED=false`, so the browser sends no token, and the server has no bearer-token validation for the Cognito tokens the Lambda path uses. From reading the code (not from a running test), signed-out API calls redirect to the Spring login URL, and the browser cannot follow that cross-origin, so **creating a trip will fail**. Decide one of these before go-live (see section 11):
>    - **A.** Add Cognito JWT validation to the `prod` profile (`oauth2ResourceServer`) and build the web app with `VITE_AUTH_REQUIRED=true`. This is a code change.
>    - **B.** Deploy the Lambda/CloudFront stack in `infra/template.yaml` instead. It is the path the repo's pipeline already deploys, and its auth flow is wired end to end. See `docs/AWS-DEPLOYMENT.md`.
> 2. **The apex `vasolai.com` cannot be a CNAME.** DNS does not allow a CNAME at the zone apex. Use a Route 53 alias record (or your registrar's ALIAS/ANAME feature) for `vasolai.com`, and a CNAME for `www.vasolai.com`. Section 8 covers both cases.
> 3. **Nothing has been deployed from this repository.** The manifests were corrected and parse as valid YAML, but no cluster, image, or DNS record exists yet. Every value written as `REPLACE_...` must be filled in.

---

## 0. What you will end up with

```
Browser ──HTTPS──> Route 53 / DNS (vasolai.com, www) ──> ALB (AWS Load Balancer Controller, from Ingress)
                                                              │ HTTP to pod IP (target-type: ip)
                                                              ▼
                                  EKS namespace "vamsdest": Service → Deployment (1 pod, Spring Boot + React)
                                                              │ IRSA role
                                                              ▼
                              Amazon Bedrock (Claude) · Secrets Manager (Tavily key) · Cognito (OIDC sign-in)
```

| Piece | Repo file | You create it |
|---|---|---|
| Container image | `Dockerfile` | ECR repository, pushed image (step 3) |
| EKS cluster | none | `eksctl` (step 4) |
| Load balancer controller | none | Helm chart + IAM role (step 5) |
| App IAM role (IRSA) | `deploy/k8s/serviceaccount.yaml` | IAM role and policy (step 6) |
| Runtime settings | `deploy/k8s/configmap.yaml` | Edit values (step 9) |
| Runtime secrets | referenced by `deployment.yaml` | `kubectl create secret` (step 8) |
| Deployment, Service, Ingress | `deploy/k8s/*.yaml` | `kubectl apply -k` (step 10) |
| Certificate | none | ACM certificate in the cluster's region (step 7) |
| DNS records | none | Route 53 or registrar (step 11) |

---

## 1. Choose names and region

Use one region for the cluster, the ALB, and the ACM certificate. This guide uses `us-east-1`. Bedrock model access is per account and per region, so pick a region where your Claude model is enabled.

Set these in your Git Bash session (repeat them in any new terminal):

```bash
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export CLUSTER_NAME=vamsdest
export ECR_REPO=vamsdest
export DOMAIN=vasolai.com
export ENV_NAME=prod
echo "Account: $AWS_ACCOUNT_ID  Region: $AWS_REGION"
```

**Check:** the account ID prints. If `aws sts get-caller-identity` fails, see section 2.

---

## 2. Install tools and sign in

| Tool | Minimum | Install check |
|---|---|---|
| AWS CLI v2 | 2.15 | `aws --version` |
| kubectl | 1.30 | `kubectl version --client` |
| eksctl | 0.190 | `eksctl version` |
| Helm | 3.14 | `helm version` |
| Docker | 24 | `docker --version` |

These are not installed on the machine this repo was checked out on (no `aws`, `kubectl`, `eksctl`, `helm`, or `sam` were found). Install them first.

Sign in to AWS with an IAM user or SSO profile that has administrator rights for the first setup. Run this yourself, since it is interactive:

```bash
aws configure sso        # or: aws configure
aws sts get-caller-identity
```

**Check:** `get-caller-identity` returns your account ID and the expected user or role.

---

## 3. Create the ECR repository and push the image

The `Dockerfile` builds the React app and the Spring Boot jar in three stages. Build from the repository root.

```bash
aws ecr create-repository --region $AWS_REGION --repository-name $ECR_REPO \
  --image-scanning-configuration scanOnPush=true --image-tag-mutability IMMUTABLE

aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

export IMAGE_TAG=0.1.0-$(git rev-parse --short HEAD)
export IMAGE_URI=$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG

docker build -t "$IMAGE_URI" .
docker push "$IMAGE_URI"
echo "$IMAGE_URI"
```

**Check:** the push finishes and ECR shows the tag. Keep `IMAGE_URI`; step 10 needs it.

Notes:
- The tag is immutable, so each build gets a new tag. Do not reuse `0.1.0`.
- The `Dockerfile` installs no `VITE_COGNITO_*` values, so the web build is the anonymous build. See the first warning at the top of this document.

---

## 4. Create the EKS cluster

This creates a managed node group with two `t3.medium` nodes (about 2 vCPU and 4 GiB each) and an IAM OIDC provider for IRSA. Both are needed by the steps below.

```bash
eksctl create cluster \
  --name $CLUSTER_NAME \
  --region $AWS_REGION \
  --version 1.31 \
  --nodegroup-name standard \
  --node-type t3.medium \
  --nodes 2 --nodes-min 2 --nodes-max 3 \
  --managed \
  --with-oidc

aws eks update-kubeconfig --region $AWS_REGION --name $CLUSTER_NAME
kubectl get nodes
```

**Check:** `kubectl get nodes` shows two nodes in `Ready`. Note that `--version 1.31` is an example. Pick a version that EKS currently supports.

Get the OIDC issuer ID. You need it for the IRSA role in step 6:

```bash
export OIDC_ISSUER=$(aws eks describe-cluster --name $CLUSTER_NAME --region $AWS_REGION \
  --query "cluster.identity.oidc.issuer" --output text | sed 's#https://##')
echo "$OIDC_ISSUER"
```

---

## 5. Install the AWS Load Balancer Controller

The Ingress in `deploy/k8s/ingress.yaml` (class `alb`) needs this controller to create the ALB. Without it, the Ingress never gets an address.

1. Create the controller's IAM policy from the upstream file (download the policy document for the latest v2.x release, from the aws-load-balancer-controller project's `docs/install/iam_policy.json`):

   ```bash
   curl -o lbc-iam-policy.json <URL-of-iam_policy.json-for-latest-v2-release>
   aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy \
     --policy-document file://lbc-iam-policy.json
   ```

2. Create a service account bound to that policy with `eksctl`:

   ```bash
   eksctl create iamserviceaccount \
     --cluster $CLUSTER_NAME --region $AWS_REGION \
     --namespace kube-system --name aws-load-balancer-controller \
     --attach-policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy \
     --approve
   ```

3. Install the controller with Helm. Get the VPC ID from `eksctl` output or `aws eks describe-cluster`:

   ```bash
   helm repo add eks https://aws.github.io/eks-charts && helm repo update
   export VPC_ID=$(aws eks describe-cluster --name $CLUSTER_NAME --region $AWS_REGION \
     --query "cluster.resourcesVpcConfig.vpcId" --output text)
   helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
     -n kube-system --set clusterName=$CLUSTER_NAME \
     --set serviceAccount.create=false --set serviceAccount.name=aws-load-balancer-controller \
     --set region=$AWS_REGION --set vpcId=$VPC_ID
   kubectl -n kube-system rollout status deployment/aws-load-balancer-controller
   ```

**Check:** the controller deployment rolls out. `kubectl get ingressclass` lists `alb`.

---

## 6. Create the app's IAM role (IRSA)

The pod calls Bedrock and reads the Tavily key from Secrets Manager. It gets credentials through a role bound to the `vamsdest` service account, not through access keys.

1. Create the Tavily secret. Paste the key into a temporary file, never on the command line:

   ```bash
   notepad tavily.key          # paste the key, save, close
   aws secretsmanager create-secret --region $AWS_REGION \
     --name vamsdest/$ENV_NAME/tavily-api-key \
     --secret-string file://tavily.key
   rm tavily.key
   export TAVILY_SECRET_ARN=$(aws secretsmanager describe-secret --region $AWS_REGION \
     --secret-id vamsdest/$ENV_NAME/tavily-api-key --query ARN --output text)
   ```

2. Write the permission policy. Restrict it to your model and secret:

   ```bash
   cat > vamsdest-app-policy.json <<EOF
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Sid": "InvokeClaude",
         "Effect": "Allow",
         "Action": "bedrock:InvokeModel",
         "Resource": [
           "arn:aws:bedrock:*:$AWS_ACCOUNT_ID:inference-profile/us.anthropic.claude-sonnet-5-5",
           "arn:aws:bedrock:*::foundation-model/anthropic.*"
         ]
       },
       {
         "Sid": "ReadTavilyKey",
         "Effect": "Allow",
         "Action": "secretsmanager:GetSecretValue",
         "Resource": "$TAVILY_SECRET_ARN"
       }
     ]
   }
   EOF
   aws iam create-policy --policy-name vamsdest-$ENV_NAME-app --policy-document file://vamsdest-app-policy.json
   ```

   Add `bedrock:ApplyGuardrail` only if you use a guardrail (section 9).

3. Create the IRSA role bound to the `vamsdest` service account in the `vamsdest` namespace:

   ```bash
   kubectl create namespace vamsdest --dry-run=client -o yaml | kubectl apply -f -
   eksctl create iamserviceaccount \
     --cluster $CLUSTER_NAME --region $AWS_REGION \
     --namespace vamsdest --name vamsdest \
     --role-name vamsdest-$ENV_NAME-app-irsa \
     --attach-policy-arn arn:aws:iam::$AWS_ACCOUNT_ID:policy/vamsdest-$ENV_NAME-app \
     --override-existing-serviceaccounts --approve
   export IRSA_ROLE_ARN=$(aws iam get-role --role-name vamsdest-$ENV_NAME-app-irsa --query Role.Arn --output text)
   echo "$IRSA_ROLE_ARN"
   ```

   `eksctl` writes the `eks.amazonaws.com/role-arn` annotation onto the service account. Put the same ARN in `deploy/k8s/serviceaccount.yaml` in step 9, or the repo file will overwrite it on the next apply.

4. Enable Bedrock model access in the Bedrock console for `$AWS_REGION` (for the model you chose). This is a console step, not a CLI one.

**Check:** `aws secretsmanager describe-secret` returns the ARN, and `aws iam get-role` returns the role.

---

## 7. Request the TLS certificate (ACM)

The ALB needs a certificate in the same region as the cluster. Request one that covers both names:

```bash
export CERT_ARN=$(aws acm request-certificate --region $AWS_REGION \
  --domain-name $DOMAIN \
  --subject-alternative-names www.$DOMAIN \
  --validation-method DNS \
  --query CertificateArn --output text)
echo "$CERT_ARN"

aws acm describe-certificate --region $AWS_REGION --certificate-arn "$CERT_ARN" \
  --query "Certificate.DomainValidationOptions[].ResourceRecord" --output table
```

The last command prints one **CNAME name and value** for each domain. Add those records in your DNS (section 8, step A1 or B1). Then wait until the certificate status is `ISSUED`:

```bash
aws acm describe-certificate --region $AWS_REGION --certificate-arn "$CERT_ARN" --query Certificate.Status --output text
```

**Check:** the status changes from `PENDING_VALIDATION` to `ISSUED`. This can take a few minutes after the DNS records exist. Do not continue until it is `ISSUED`, or the ALB will not serve HTTPS.

---

## 8. DNS: where the CNAME changes go

Find out where `vasolai.com` is hosted before you change anything:

```bash
dig +short NS vasolai.com
```

- If the name servers are `awsdns-*` (Route 53), use **Option A**.
- Otherwise (GoDaddy, Namecheap, Cloudflare, and similar), use **Option B**.

The ALB hostname is not known until the Ingress exists (step 10). Steps that need it say so.

### Option A: DNS in Route 53

A1. **Certificate validation records.** In the `vasolai.com` hosted zone, create each CNAME that step 7 printed (name and value exactly as shown).

A2. **Apex and www after the Ingress exists.** Get the ALB hostname:

```bash
ALB_HOST=$(kubectl get ingress vamsdest -n vamsdest -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "$ALB_HOST"
```

Look up the ALB's Route 53 zone ID (it differs by region):

```bash
ALB_ZONE_ID=$(aws elbv2 describe-load-balancers --region $AWS_REGION \
  --query "LoadBalancers[?DNSName=='$ALB_HOST'].CanonicalHostedZoneId" --output text)
export ZONE_ID=$(aws route53 list-hosted-zones-by-name --dns-name $DOMAIN \
  --query "HostedZones[?Name=='$DOMAIN.'].Id" --output text | sed 's#/hostedzone/##')
```

Create the alias for the apex (`vasolai.com`, type `A`), and the same for `AAAA` if the ALB has IPv6 enabled. Use `CNAME` for `www`:

```bash
cat > dns-apex.json <<EOF
{ "Changes": [
  { "Action": "UPSERT", "ResourceRecordSet": {
      "Name": "$DOMAIN", "Type": "A",
      "AliasTarget": { "HostedZoneId": "$ALB_ZONE_ID", "DNSName": "$ALB_HOST", "EvaluateTargetHealth": true } } },
  { "Action": "UPSERT", "ResourceRecordSet": {
      "Name": "www.$DOMAIN", "Type": "CNAME", "TTL": 300,
      "ResourceRecords": [ { "Value": "$ALB_HOST" } ] } }
]}
EOF
aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch file://dns-apex.json
```

### Option B: DNS at another registrar

B1. **Certificate validation records.** At your DNS provider, add each CNAME from step 7.

B2. **www.** After the Ingress exists (step 10), create:
- Type `CNAME`, name `www`, value = the ALB hostname from `kubectl get ingress vamsdest -n vamsdest -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'`, TTL 300.

B3. **Apex (`vasolai.com`).** A CNAME is not allowed here. Use one of:
- Your provider's **ALIAS / ANAME / CNAME flattening** record, pointed at the ALB hostname (Cloudflare, DNSimple, and similar support it), or
- **Move the nameservers** to a Route 53 hosted zone (Option A), which is the simplest and most reliable choice for an ALB, or
- A **redirect** from `vasolai.com` to `https://www.vasolai.com`, which only works if your provider offers apex redirects.

### Verify DNS

```bash
dig +short www.vasolai.com
dig +short vasolai.com
```

**Check:** both names resolve to ALB addresses. DNS can take several minutes to propagate. A name that resolves on Google (`nslookup vasolai.com 8.8.8.8`) but not locally just needs time.

---

## 9. Set the runtime configuration

### 9a. Non-secret values: `deploy/k8s/configmap.yaml`

Set these before applying:

| Key | Value | Notes |
|---|---|---|
| `AWS_REGION` | `us-east-1` | Same as the cluster |
| `BEDROCK_MODEL_ID` | `us.anthropic.claude-sonnet-5-5` | Must match your enabled model |
| `BEDROCK_GUARDRAIL_ID` | `""` (empty) **or** a real guardrail ID | The file currently holds the placeholder `REPLACE_WITH_BEDROCK_GUARDRAIL_ID`, which is not a valid ID. Set it to `""` for no guardrail. |
| `BEDROCK_GUARDRAIL_VERSION` | `""` (empty) **or** the version | Set **both** guardrail values, or neither. The worker refuses to start with only the ID. |
| `AGENT_MAX_SEARCHES` | `"4"` | Caps Tavily spend per agent |

Also note: `SPRING_PROFILES_ACTIVE: prod` is required, because the production security chain and OIDC settings are only loaded under that profile.

### 9b. Secret values: `vamsdest-runtime`

Create the runtime secret in the cluster. It has four keys. These values come from Cognito (step 9c) and Secrets Manager (step 6):

| Key | Where it comes from |
|---|---|
| `TAVILY_API_KEY` | The Tavily key from step 6 (read it from Secrets Manager, or paste it) |
| `OIDC_ISSUER_URI` | `https://cognito-idp.$AWS_REGION.amazonaws.com/<USER_POOL_ID>` |
| `OIDC_CLIENT_ID` | The confidential app client ID (step 9c) |
| `OIDC_CLIENT_SECRET` | The app client secret (step 9c) |

Write the values to a file, not the command line, and delete the file after:

```bash
cat > runtime-secrets.env <<EOF
TAVILY_API_KEY=<paste>
OIDC_ISSUER_URI=https://cognito-idp.$AWS_REGION.amazonaws.com/<USER_POOL_ID>
OIDC_CLIENT_ID=<paste>
OIDC_CLIENT_SECRET=<paste>
EOF
kubectl -n vamsdest create secret generic vamsdest-runtime --from-env-file=runtime-secrets.env
rm runtime-secrets.env
```

`.gitignore` already excludes `.env.*` and `deploy/runtime-secrets.env`. Keep it that way.

### 9c. Cognito for sign-in (only if you keep the pods auth path)

The `prod` profile needs an OIDC provider. Cognito is the one this project already uses. The Cognito pool from the Lambda stack has a **public** client (no secret), but Spring's `oauth2Login` needs a **confidential** client. Create a new one:

```bash
aws cognito-idp create-user-pool-client --region $AWS_REGION \
  --user-pool-id <USER_POOL_ID> \
  --client-name vamsdest-pods-$ENV_NAME \
  --generate-secret \
  --allowed-o-auth-flows code \
  --allowed-o-auth-scopes openid email profile \
  --allowed-o-auth-flows-user-pool-client \
  --callback-urls https://$DOMAIN/login/oauth2/code/vamsdest \
  --logout-urls https://$DOMAIN/ \
  --supported-identity-providers COGNITO
```

Use the returned `ClientId` and `ClientSecret` in step 9b. Add `https://www.$DOMAIN/login/oauth2/code/vamsdest` to the callback URLs if you serve `www`.

If you pick option A from the warning at the top (bearer tokens), this client changes. Ask before creating it.

---

## 10. Set placeholders and deploy

1. Edit the placeholders in these files:

   | File | Placeholder | Replace with |
   |---|---|---|
   | `deploy/k8s/deployment.yaml` | `REPLACE_WITH_ECR_IMAGE_URI:0.1.0` | `$IMAGE_URI` from step 3 (the full image URI including tag) |
   | `deploy/k8s/serviceaccount.yaml` | `REPLACE_WITH_IRSA_ROLE_ARN` | `$IRSA_ROLE_ARN` from step 6 |
   | `deploy/k8s/ingress.yaml` | `REPLACE_WITH_ACM_CERTIFICATE_ARN` | `$CERT_ARN` from step 7 |

   The `ingress.yaml` WAF annotation is commented out. Enable it only after you create a **regional** WAF web ACL in this region.

2. Check the manifests render before applying:

   ```bash
   kubectl kustomize deploy/k8s | grep -n "REPLACE_" && echo "Placeholders remain" || echo "No placeholders"
   ```

   Fix anything it reports. Do not apply with `REPLACE_` values still in place.

3. Apply:

   ```bash
   kubectl apply -k deploy/k8s
   kubectl -n vamsdest rollout status deployment/vamsdest --timeout=10m
   ```

**Check:** the rollout finishes. If it times out, go to section 12.

---

## 11. Verify the site

1. **Pod health.** The probes use `/actuator/health/readiness` and `/actuator/health/liveness`:

   ```bash
   kubectl -n vamsdest get pods
   kubectl -n vamsdest logs deploy/vamsdest --tail=50
   ```

2. **Ingress has an address and targets are healthy.** Wait for the ALB to show healthy targets (usually 1–3 minutes):

   ```bash
   kubectl -n vamsdest get ingress vamsdest
   ```

3. **HTTPS works and redirects.** Once DNS has propagated (section 8):

   ```bash
   curl -sI http://vasolai.com | head -1               # expect 301 to https
   curl -s https://vasolai.com/actuator/health/readiness # expect {"status":"UP"}
   curl -sI https://www.vasolai.com | head -1           # expect 200
   ```

4. **The page loads in a browser** at `https://vasolai.com`. The page is served by the Spring app from its built-in static files (the `Dockerfile` copies the web build into `static/`).

5. **Create a trip (this is the test that exposes the auth gap).** If the form returns an error, or the browser tries to reach a Cognito or `/oauth2` page, the auth gap from the top of this document is still open. The fix is code (option A), or switch to the Lambda path (option B).

---

## 12. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Pod in `CrashLoopBackOff`, log says `Could not resolve placeholder 'OIDC_CLIENT_ID'` | `vamsdest-runtime` secret missing or missing a key | Step 9b. Check with `kubectl -n vamsdest describe secret vamsdest-runtime` (keys only). |
| Pod never becomes `Ready`, readiness probe fails | Port or probe path mismatch, or startup is slow | `kubectl -n vamsdest describe pod`; check logs. The startup probe allows about 150 seconds. |
| Ingress has no address | Load balancer controller not installed or failing | `kubectl -n kube-system logs deploy/aws-load-balancer-controller`. Check that the VPC ID and IAM policy are right. |
| ALB returns 502/503 | Target group unhealthy | Check the target group in the EC2 console, the pod's readiness, and the security group rules (the controller creates them). |
| Certificate stays `PENDING_VALIDATION` | Validation CNAME missing or wrong | Re-check the exact name and value in section 7 against your DNS. |
| Browser shows a certificate error | Certificate does not cover the hostname you used | The certificate must list `vasolai.com` and `www.vasolai.com`. |
| Research runs fail with `AccessDenied` from Bedrock | Model access not enabled, or IRSA role missing | Step 6.4 (console); check the service account annotation with `kubectl -n vamsdest get sa vamsdest -o yaml`. |
| Research fails with `TAVILY_API_KEY is required` | Secret key empty | Step 9b. |
| Pod logs `ExitOnOutOfMemoryError` | Heap too small for the node | Raise the memory limit in `deployment.yaml` (default 1 Gi) or the heap percentage. |

---

## 13. Updating and rolling back

Build and push a new immutable tag (step 3), change `deploy/k8s/deployment.yaml`, and re-apply:

```bash
kubectl -n vamsdest set image deployment/vamsdest app=$NEW_IMAGE_URI
kubectl -n vamsdest rollout status deployment/vamsdest
```

Roll back to the previous release:

```bash
kubectl -n vamsdest rollout undo deployment/vamsdest
```

The deployment uses the `Recreate` strategy with one replica, so there is a short downtime on each release.

---

## 14. Tear down (to stop charges)

Delete in this order, so the ALB and its security groups are removed by the controller:

```bash
kubectl delete -k deploy/k8s
helm uninstall aws-load-balancer-controller -n kube-system
eksctl delete cluster --name $CLUSTER_NAME --region $AWS_REGION
aws ecr delete-repository --region $AWS_REGION --repository-name $ECR_REPO --force
```

Also remove the DNS records you created and the ACM certificate if you do not need it. Delete the Tavily secret only after you have a copy of the key.

---

## 15. Open items

- **Auth path.** Choose option A (add JWT validation to `prod`, and build the web app with `VITE_AUTH_REQUIRED=true`) or option B (deploy `infra/template.yaml`). Section 11 step 5 shows how to confirm which one you need.
- **Single pod, in-memory runs.** The pods path stores runs in memory (`InMemoryRunStore`), so a pod restart loses in-flight research. `replicas: 1` is required until runs are moved to a shared store.
- **Frontend API base.** The web app calls `/api/*` on the same origin, so no separate API hostname is needed on this path.
- **Email and alerts.** The pods path has no CloudWatch alarms. Add them to the cluster or use the Lambda stack's alarms.
