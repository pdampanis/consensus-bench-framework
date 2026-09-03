# NEXT SESSION — read this first, then `docs/PROJECT_STATE.md`

> Written 2026-09-03 at commit `e0de6ae`. If today's date is much later,
> treat every number here as a claim to re-verify, not a fact. The commands
> to re-verify are all here.

---

## 0. Orient — 60 seconds, and do not skip it

**You are probably in the wrong directory.** The repo's natural-looking path
is a stale worktree.

```bash
cd ~/Downloads/cbt-main          # ← THE ONE WITH THE WORK
git worktree list
git status --short               # expect: clean
git log --oneline -3
```

| Worktree | Branch | Use it? |
|---|---|---|
| **`~/Downloads/cbt-main`** | **`ninth-review-handoff`** | **YES — canonical** |
| `~/Downloads/cbt-eighth` | `eighth-review-tier1` | no — tree-identical to the merge |
| `~/Downloads/consensus-bench-thesis` | `seventh-review-tier1` | **no — STALE.** Five weeks behind, no `thesis/` at all |

If a doc and `git` disagree, **git wins — then fix the doc.**

---

## 1. What this is

A master's thesis: *Evolution and Comparison of Consensus Protocols in
Distributed Systems*. One repo holds both halves:

- **`harness/`** — a Java 21 benchmark for **seven** systems: etcd (Raft),
  Kafka KRaft, Kafka+ZooKeeper (ZAB), CometBFT (Tendermint), Paxos, EPaxos
  (both via Paxi), HotStuff. Open-loop load with coordinated-omission
  correction, HdrHistogram, an 11-gate `ValidityChecker`.
- **`thesis/`** — the bilingual LaTeX document the benchmark exists to
  produce. Chapters 1–2 written in EN and EL; the experimental chapters wait
  on the campaign. Its own checks: `thesis/README.md`.

Measurements will come from Hetzner VMs. **Nothing has been billed yet.**

---

## 2. Verified state — every number has a command behind it

| Claim | Command |
|---|---|
| `Tests run: 248, Failures: 0, Errors: 0`, BUILD SUCCESS, 05:24, 37/37 classes | `cd harness && ~/tools/maven/mvn21.sh clean verify` |
| `analyse.py --selftest` OK | `python3 analysis/analyse.py --selftest` |
| Thesis checks pass (EN/EL parity, citations, corpus↔bib) | `./scripts/check_alignment.sh` |
| Branch pushed, tree clean | `git status --short && git log --oneline origin/ninth-review-handoff..HEAD` |

**`main` is 30 commits ahead of `origin/main` and deliberately NOT pushed.**
Merge gate 6 (the golden read-through) blocks it. Do not push `main`.

---

## 3. Traps that have already cost time

1. **Default `javac` is Java 8** and cannot compile this. Use
   `/usr/lib/jvm/amazon-corretto-21.0.7.6.1/bin/javac` and `.../bin/java`.
2. **Read the test count from Maven's own `Tests run:` summary line** — never
   from arithmetic over `surefire-reports/`. That is F76: a `verify` once
   reported BUILD SUCCESS having run 90 of 177 tests. A run under 4 minutes is
   a **failed gate** until the count is confirmed. Do not pipe `verify` to
   `tail` — you will throw that line away.
3. **Not installed here:** `terraform`, `hcloud`, `latexmk`, `xelatex`,
   `pandoc`. So `cd thesis && make` fails; the Python checks do not need them.
4. **Background `mvn verify` was killed twice** in the 2026-09-03 session,
   surfacing as
   `SurefireBooterForkException: The forked VM terminated without properly
   saying goodbye`. It was **not** a test failure and **not** an OOM (no
   `.dump` files, nothing in `dmesg`). Run `verify` in the **foreground**.
5. **Disk was at 88% (4.5 GB free on `/`)** on 2026-09-03. The build spins
   Docker containers. Check `df -h /` before a long run.

---

## 4. The rules — non-negotiable, from `PROJECT_STATE` §9

1. **Execute, don't assert.** If it compiles, runs or parses, do it and show
   the output before claiming anything.
2. **TDD strictly.** Failing test first, watched red *for the right reason*. A
   compile error is a weak red; get a behavioural one.
3. **Never `terraform apply`**, and never call the Hetzner API. `validate` and
   dummy-token `plan` are fine. This is the G2 gate.
4. **Laptop numbers are functional evidence only — never thesis data.**
5. **§9.7 — thesis alignment.** Every change to `harness/`, `analysis/` or
   `corpus/` requires a thesis review **in the same session**. Run
   `./scripts/check_alignment.sh` and say which chapters you checked.
   *"No thesis impact"* is a finding you must write down; it is not the same
   as not looking.
6. **Never widen a gate to make a build pass.**

---

## 5. What to do next — Phase 0, and it is all free

`PROJECT_STATE` §6 is the full plan with acceptance criteria. Remaining:

| # | Step | Note |
|---|---|---|
| **0.1 + 0.5b** | **C1 + C2 together** | Do them as one increment. C1 alone makes HotStuff `valid:true` and still yields a cell of `{n:1}`, because `analyse.py` never opens `summary.txt` |
| 0.2 | **M2** — route `tendermint` to the gauge witness | Discards every CometBFT `kill`/`double_kill` today |
| 0.4 | **N-20** — etcd backend quota | **The only golden-touching item.** Must land before G2 |
| 0.5 | **C4** — remove the Kafka RF cap at 3 | Client-side; verify the size-3 golden is unchanged by running it |
| 0.6 + 0.7 | `deploy_obs.sh` and `render_obs_config.sh` | **The campaign-killer pair.** Without them every run is `valid:false` and the whole campaign is discarded — while billing in full |
| 0.8 + 0.9 | `main.tf` and cloud-init hardening | Patches in the handoff pack apply clean |
| 0.10 + 0.11 | Circuit breaker, disk guard, watchdog | The shipped breaker patch is **stale** — re-implement |
| 0.12 | M5/M8/M9 | Grade **A** is currently unreachable |
| 0.14 | Doc corrections | Listed in §6 |
| 0.15 | `bootstrap_loadgen.sh` | B-1/B-2/B-3 — the loadgen cannot run the harness without it |

**Suggested first increment:** 0.1 + 0.5b. It is the largest single recovery
of data (all of HotStuff), and it is pure code with no infrastructure.

---

## 6. WHAT ONLY YOU CAN DO — the manual list

Nothing below can be delegated to a model. Items 1–3 block the paid work.

### 1. G2 — the golden read-through  ⛔ BLOCKS EVERYTHING BILLED
Read all seven files in `harness/src/test/resources/goldens/` against the
checklist in each header, then sign and date it. Five have changed since the
ledger last called them FINAL (the F69 host sweep, the derived `slow_node`
timeout, packet-loss severity), and Phase 0.4 will change the etcd one again.
**Do 0.4 first, then sign once against final text.** This is merge gate 6 and
it is the only thing keeping `main` unpushed.

### 2. Hetzner account and credentials
- Create the project; **Security → API tokens → Read & Write**. Shown once.
- `ssh-keygen -t ed25519 -f ~/.ssh/hetzner_thesis -N ""` — it does not exist yet.
- Install `terraform` (≥1.6) and `hcloud`. Neither is on this machine;
  `docs/FIRST_MANUAL_DEPLOY.md` S0 has a sudo-free download into `~/bin`.

### 3. The price check — free, and a STOP gate
```bash
hcloud server-type list -o json | jq '.[].prices'
```
**Every euro in this repo was written down from a web page in July 2026 and
never read back from Hetzner.** If `ccx13` moved or `cpx21` is gone, edit
`infra/main.tf` `local.hourly_eur` **before** applying — `main.tf:196` scores
an unpriced type as **0**, so the cost output would silently under-report.

### 4. The first deployment — by hand
`docs/FIRST_MANUAL_DEPLOY.md`, S0–S13, at **n=3**. You type `terraform
apply`; no model does. Budget **≈€1.10 for 3 hours** and set a phone timer —
Hetzner bills a partial hour as a full one. Expect `Plan: 11 to add`.

### 5. Two decisions still open
- **0.13** — purge the signed application PDF from `corpus/` history? It is
  **already on GitHub** (initial commit `6289132`), so the exposure has
  happened. The fix is a history rewrite **plus** asking GitHub Support to GC
  the unreachable objects; a rewrite alone leaves the blob fetchable by SHA.
  Regenerate `corpus/MANIFEST.tsv` in the same commit or `corpus.py verify`
  will fail.
- **D-4** — a date for the E-CTRL deadline. Chapters 5 and 8 cannot be
  scheduled without it.

### 6. Thesis judgement calls
- Fill the **12 blank `bibkey` cells** in `corpus/MANIFEST.tsv`. Six bib
  entries claim `VERIFIED-PDF` with no file mapped to them; `corpus.py verify`
  names both lists.
- **8 of 9 `note` fields in `thesis/bib/references.bib` are in Greek**, and
  `note` is language-neutral in BibTeX — they render verbatim in the English
  PDF. Entries: `[7] [9] [17] [18] [19] [28] [34]`.
- **`corpus/Accountable_Tendermint___DSN_2022.pdf` is not that paper.** It is
  byte-identical to *Revisiting Tendermint: Design Tradeoffs*. Rename it
  before anyone cites it by its filename.
- §1.3 *"Why the comparison is still an open question"* does not engage
  **Sondhi's** empirical comparison of consensus algorithms, which is in your
  own corpus. That is the first prior work an examiner will ask about.

---

## 7. Where to read further

| File | What it is |
|---|---|
| **`docs/PROJECT_STATE.md`** | **THE DRIVER.** §5 open issues, §6 the six phases, §7 the merge runbook, §9 the working agreement |
| `docs/FIRST_MANUAL_DEPLOY.md` | The first deployment, command by command |
| `docs/HANDOFF_REVIEW_2026-09-02.md` | Evidence ledger — §7 says what was *not* verified |
| `CLAUDE.md` | Session rules, the thesis-alignment rule, the build |
| `thesis/README.md` | The LaTeX build and its checks |
| [The plan as one page](https://claude.ai/code/artifact/466a87d7-247c-4dbd-a128-752d0bf33c46) | Published summary; source `docs/artifact/plan.html` |

`docs/archive/` is **history, not guidance** — but code comments cite its
F-numbers, so it stays.
