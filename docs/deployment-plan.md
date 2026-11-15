# Deployment plan (not deployed)

Updated 2026-11-16 for the **zero-cost rule** (CLAUDE.md hard rule 10): no paid plan, no paid API, no trial that
auto-charges, and nothing that needs billing turned on or a payment card. Every host below was checked that day
on its official page (linked). Nothing was signed up for, bought or deployed.

**Bottom line: no free host can run Sift as it is today, all the time, without a card.** The plan is
**localhost + a demo video** (section 3). One host can run it **for a few weeks** with no card (Railway's trial),
which is useful only as a short live window, for example during interviews (section 4).

## 1. What the server needs (measured, `eval/results/phase7-docker.json`)
| | Measured in Docker |
| --- | --- |
| Image | 553 MB compressed (1.1 GB unpacked); `linux/amd64` build tested |
| Memory | 795 MiB at start, 900 MiB after 1,200 queries; **836 MiB with a 1 GiB limit under a 10-user load test, no out-of-memory kill** |
| Startup | 2.3 s to healthy (laptop, native) |
| CPU per query | keyword ~1–2 ms, Smart ~6–10 ms (one core) |

So a host must give **at least 1 GiB of memory**. A 512 MB plan cannot run it.

## 2. Free hosting options (checked 2026-11-16)

| Host | Free memory | Sleeps when idle? Wake-up | Card needed? | Time limit | Fits Sift? |
| --- | --- | --- | --- | --- | --- |
| **Render** Free web service | 512 MB, 0.1 CPU | Yes, after 15 min without traffic; waking "takes about one minute" | Not required by the free docs | 750 free instance hours per workspace per month | **No**: 512 MB < ~800 MiB at start |
| **Koyeb** Free Instance | 512 MB, 0.1 vCPU, 2 GB disk | Yes, scales to zero after 1 hour without traffic; wake time not stated | Not stated | One free instance per organization | **No**: 512 MB |
| **Railway** Free plan | 0.5 GB per service | Not stated | No | $1 of usage per month | **No**: 0.5 GB |
| **Railway** Trial | **1 GB per service**, up to 2 vCPU | Not stated | **No** ("Can I try Railway without a credit card? Yes.") | **$5 of credit that expires after 30 days** | **Only for a short time** (section 4) |
| **Hugging Face Spaces** (Docker) | CPU Basic: 2 vCPU, 16 GB, free hardware | Yes, free hardware "will go to sleep … if unused" | — | — | **No**: "Gradio and Docker Spaces run on compute and require a paid plan to create" (PRO). Only static Spaces are free, and they cannot run a server |
| **Northflank** Sandbox | Free plan sizes not published ("Free projects have separate limits") | Pricing page: always on | Pricing page: "No card needed"; Railway's comparison page says a payment method is needed | Up to $20/month of resources | **Unclear**: cannot be confirmed without signing up. Not recommended until the limits are public |
| **Fly.io** | — | — | **Yes**: "All organizations … require a credit card on file" | Trial: $1 of credit for 7 days | **No** (card, no free tier: "New organizations don't have a free tier") |
| **Google Cloud Run** | Free tier would cover part of it | — | **Yes**: "A Google Cloud billing account is required to access the Google Cloud Free Tier" | — | **No**: needs billing turned on (breaks rule 10) |
| **Oracle Cloud** Always Free | Ampere A1: 2 OCPUs, 12 GB | Idle instances can be reclaimed (7 days under 20% CPU, network and memory) | **Yes**: sign-up asks to "Add payment verification method … Credit Card" | None | **No**: needs a card (breaks rule 10) |

Sources (all checked 2026-11-16):
- Render: https://render.com/docs/free (spin-down, 750 hours) and https://render.com/docs/compute-plans
  (Free = 0.1 CPU, 512 MB).
- Koyeb: https://www.koyeb.com/docs/reference/instances.
- Railway: https://railway.com/pricing (Trial and Free plan limits, no card, $5 for 30 days; usage prices about
  $10 per GB of memory per month and $20 per vCPU per month).
- Hugging Face: https://huggingface.co/docs/hub/spaces-overview (paid plan needed for Docker Spaces; CPU Basic
  specs; sleep).
- Northflank: https://northflank.com/pricing and
  https://northflank.com/docs/v1/application/billing/resource-plans-and-quotas. The opposite claim about the card
  is on https://docs.railway.com/platform/compare-to-northflank.
- Fly.io: https://docs.fly.io/about/pricing.
- Google Cloud: https://docs.cloud.google.com/free/docs/free-cloud-features.
- Oracle: https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm and
  https://docs.oracle.com/en-us/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm.

The 2026-11-14 version of this file recommended Google Cloud Run (about $15–20/month) with Render Standard
($25/month) as the fallback. **Both are dropped**, because they cost money and need a card.

## 3. The plan: localhost + demo video
Sift runs well on a laptop: 2.3 s to start, 6 ms per keyword query. A short video shows that better than a link
that might be asleep.

**A. Make it easy to run** (already done): `docs/run-locally.md`. Build the website, then `scripts/sift-api`, then
open http://localhost:8080. Building the data once is `scripts/sift-data`.

**B. Record a 60–90 second demo**, free, with tools already on a Mac:
1. Start Sift locally and open http://localhost:8080 in a clean browser window (no bookmarks bar, 1280 × 800).
2. Record with macOS screen recording (Shift + Command + 5 → "Record Selected Portion"). It is built in and free.
3. Show, in this order:
   - Typing "pyt" (autocomplete).
   - "pyhton" ("Did you mean python?").
   - "how do plants make food" in Smart mode (Photosynthesis near the top), then the same in Keyword mode.
   - "Details" (24 ms, documents searched, cache hit on a repeat).
   - Page 2.
   - Dark mode.
   - The phone layout (browser window at 375 px).
4. Keep the result as an `.mp4`. For the README GIF, convert a short part with `ffmpeg` (free; not installed on this
   Mac yet, `brew install ffmpeg`) and keep the GIF small (a few MB), so the README loads fast.

**C. Put it where people look:**
- In the README, replace "Live demo: coming soon" with "Demo video" plus the GIF.
- Upload the video as an unlisted YouTube video (a free Google account, no card), or attach the `.mp4` in the
  GitHub README editor (free).
- Say plainly in the README: "Runs locally; no live server, because this project has a zero-cost rule."

**D. In interviews:** run it live from the laptop. It needs no internet, except for the optional Discussions tab.

## 4. Optional: a short live window on Railway's trial (no card)
Only if you want a public link for a few weeks:
- Railway's trial gives 1 GB per service and $5 of credit for 30 days, with no card. Sift measured 836 MiB under
  load with a 1 GiB limit, so it should fit, but with little headroom. That must be measured on Railway itself.
- A rough estimate from Railway's published prices: about 0.85 GB × $10 per GB-month ≈ $8.50 a month for memory alone.
  So the $5 credit lasts **roughly 2–3 weeks**, not the full 30 days. This is an estimate, not a measurement.
- Railway's image size limit is not on the pricing page. The 553 MB image must be tested.
- When the credit runs out, the service stops. Without a card nothing can be charged, so this does not break rule 10.
- **Not started.** It needs your Railway account and your OK. Pick the weeks when the link matters most.

## 5. What would make an always-on free host possible (not planned)
A smaller "keyword-only" build without the meaning model and vectors (about 280 MB of the image) might fit in
512 MB on Render Free or Koyeb Free, with Smart mode turned off. Its memory has **not** been measured, so I can't say
whether it fits. It would also sleep after 15–60 minutes and take about a minute to wake on Render. That is a worse
first impression than a video, so it is listed only as an option.
