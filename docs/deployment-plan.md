# Deployment plan (not deployed yet)

Written 2026-11-14. Prices were checked that day on the official pages linked below; they can change. Nothing
here has been bought or set up. **Deploying waits for your OK on the cost.**

## What the server needs (measured, `eval/results/phase7-docker.json`)
| | Measured in Docker |
| --- | --- |
| Image | 553 MB compressed (1.1 GB unpacked); `linux/amd64` build tested (starts and answers correctly) |
| Memory | 795 MiB at start, 900 MiB after 1,200 queries; **836 MiB with a 1 GiB limit under a 10-user load test, no out-of-memory kill** |
| Startup | 2.3 s to healthy (laptop, native); 10 s for the amd64 image under emulation (an upper bound, not the server number) |
| CPU per query | keyword ~1–2 ms, Smart ~6–10 ms (one core) |

So the server needs **≥ 1 GiB of memory** (2 GiB gives headroom) and about 1 vCPU. Any 512 MB plan is too small.

## Option 1: Google Cloud Run (us-central1, Tier 1 prices)
Settings: 1 vCPU, request-based billing, min instances = 1 (as CLAUDE.md asks), max instances = 2 (a cost cap),
startup CPU boost on.

Prices from https://cloud.google.com/run/pricing (request-based billing, Tier 1):
- Active time: CPU $0.000024 per vCPU-second, memory $0.0000025 per GiB-second, $0.40 per million requests.
- Idle min instance: CPU **$0.0000025** per vCPU-second, memory **$0.0000025** per GiB-second.
- Free tier: 180,000 vCPU-seconds, 360,000 GiB-seconds and 2 million requests per month, "applied as a spending
  based discount using Tier 1 pricing".

Monthly cost of the one always-on instance (1 month = 730 h = 2,628,000 s):

| Memory | Idle cost | Free tier, if all of it applies | Expected |
| --- | --- | --- | --- |
| 2 GiB | 2,628,000 × ($0.0000025 + 2 × $0.0000025) = **$19.71** | 180,000 × $0.000024 + 360,000 × $0.0000025 = up to $5.22 | **about $14.50–19.71** |
| 1 GiB | 2,628,000 × ($0.0000025 + $0.0000025) = **$13.14** | up to $5.22 | about $7.92–13.14 |

On top of that:
- **Traffic**: tiny. For example, 100,000 queries × ~10 ms = 1,000 active vCPU-seconds × $0.0000215 extra ≈
  $0.02, plus $0.04 for the requests.
- **Image storage** (Artifact Registry, https://cloud.google.com/artifact-registry/pricing): first 0.5 GiB free, then
  $0.000136986 per GiB-hour (≈ $0.10 per GiB-month). Two image versions ≈ 1.03 GiB → about $0.05 per month.
- **Not recommended:** instance-based billing (CPU always on, $0.000018 per vCPU-second) would cost about
  $57.82 − $5.22 = $52.60 per month at 2 GiB.

Cold starts:
- With min instances = 1, one instance stays warm, so normal visitors never wait for a start.
- A *second* instance (only under heavy load) must pull the 553 MB image and start Java, which takes seconds.
  The exact time must be measured on Cloud Run. Startup CPU boost shortens it.
- With request-based billing the idle instance gets almost no CPU between requests. That is fine for this
  read-only server, which has no background work.

Memory rules (https://docs.cloud.google.com/run/docs/configuring/services/memory-limits): 1 vCPU allows up to 4 GiB;
0.5 vCPU allows at most 1 GiB.

## Option 2: Render
Prices from https://render.com/pricing:
- Workspace: **Hobby $0/month + compute**.
- Web services: Free (512 MB), Starter **$7** (512 MB, 0.5 CPU), Standard **$25** (2 GB, 1 CPU), and larger ones.
- Bandwidth: 5 GB included on Hobby, then $0.15 per GB.

| Plan | Fits? | Monthly |
| --- | --- | --- |
| Free (512 MB) | **No**: the server uses ~800 MiB just after starting. Also "Render spins down a Free web service that goes 15 minutes without receiving any inbound traffic", and waking up "takes about one minute" (https://render.com/docs/free). | $0 |
| Starter (512 MB) | **No**, same memory problem | $7 |
| Standard (2 GB, 1 CPU) | Yes, with headroom | **$25** |

Cold starts: paid services run all the time, so there are none. Deploys replace the old instance with no downtime.

Catch: Render normally builds the Dockerfile from the GitHub repo, but `data/` is not in git. So the image must be
built locally and pushed to a registry (for example GitHub Container Registry). Render then deploys that image.

## Recommendation: Google Cloud Run, 1 vCPU, 2 GiB, min 1, max 2 (about $15–20 per month)
- It is cheaper than Render's only plan that fits ($25), even at 2 GiB. After measuring the real memory on Cloud
  Run, it can drop to 1 GiB (about $8–13).
- It is what the CLAUDE.md plan names, and `max instances = 2` puts a hard ceiling on compute cost.
- It also handles load spikes by adding an instance. Render Standard is one fixed instance.
- Render stays the fallback if setting up Google Cloud is a problem. It is simpler, but costs $25 a month.

## Steps only you can do (accounts and billing)
**Google Cloud Run**
1. Sign in to https://console.cloud.google.com with a Google account and create a project (e.g. `sift-search`).
2. Turn on billing for the project (needs a payment card), then **create a budget alert** (e.g. $25 per month) under
   Billing → Budgets & alerts.
3. Install the gcloud CLI, then run `gcloud auth login` and `gcloud config set project <project-id>`.
4. Enable the two services: `gcloud services enable run.googleapis.com artifactregistry.googleapis.com`.
5. Tell me "OK to deploy at about $15–20 per month".

After that I can prepare (and you run or approve) these:
- Create an image repository in us-central1.
- `gcloud auth configure-docker us-central1-docker.pkg.dev`.
- `docker buildx build --platform linux/amd64 --push -t us-central1-docker.pkg.dev/<project>/sift/sift:<version> .`
- `gcloud run deploy sift --image … --region us-central1 --cpu 1 --memory 2Gi --min-instances 1 --max-instances 2 --cpu-boost --allow-unauthenticated`,
  with the startup probe on `/health`.

**Render (fallback)**
1. Create a Render account and add a payment method.
2. Create a GitHub token that can publish packages, so the image can be pushed to `ghcr.io` (public, or private
   with the token saved in Render).
3. In Render: New → Web Service → "Deploy an existing image", plan **Standard**, health check path `/health`.
