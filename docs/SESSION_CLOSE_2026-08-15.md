# Session close-out — 2026-08-15 (seventh review)

Written at end of day, in a hurry, on branch `seventh-review-tier1`.
Everything below is stated as verified or NOT verified — nothing inferred.

---

## 1. Repo state at close

| Worktree | Branch | Pushed? |
|---|---|---|
| `~/Downloads/consensus-bench-thesis` | `seventh-review-tier1` | YES (this doc is the last commit) |
| `~/Downloads/cbt-eighth` | `eighth-review-tier1` | YES, untouched by this session |
| `~/Downloads/cbt-main` | `main` + a LOCAL merge commit | **NO — see §4, do not assume it is safe** |

Both review branches were clean and fully pushed before this doc.
`eighth-review-tier1` **contained every commit of** `seventh-review-tier1`
(verified: `git log eighth..seventh` was empty).

---

## 2. DONE this session — three TDD increments, each gated green

The whole session started from a full code+docs read-through of HEAD
`9e9fbdd`, whose 170-test count was re-verified by execution first.

| Commit | What |
|---|---|
| `a6471e1` | **F50a** — a fault run whose fault never fired may no longer claim `complete` |
| `9e258fa` | **F50b/c** — gate 3 stops posing unmarked fault runs as "baseline"; the fault thread's join timeout is checked; its start-wait is bounded |
| `b0f790d` | **F51** — `heal()` may no longer undo a fault that is still being applied |
| `d6dceec`, `08d0a80` | the F50–F69 ledger + `PROJECT_STATE` header |

**F50 was the headline** and was proven by execution before any fix, then
re-measured after: a `leader_kill` run whose injection failed used to land
as `status: complete` + `fault_injected_at_ms: null`, which resume skipped
FOREVER, gate 3 called a "baseline run" and passed as `valid: true`, and
`analyse.py` emitted as a `leader_kill` cell carrying **undisturbed
baseline numbers**. After the three increments: `status failed` /
`alreadyComplete false` / `valid=false` with the right diagnosis /
excluded from analysis with a stated reason.

Suite **170 → 177 green** (`mvn21 clean verify`, 34 classes, read from
Maven's own `Tests run:` summary).

---

## 3. PENDING — what the next session picks up

### 3.1 The merge to main is PREPARED BUT NOT VERIFIED AND NOT PUSHED

A `--no-ff` merge of `eighth-review-tier1` into `main` exists **only
locally** in `~/Downloads/cbt-main` as commit `38eb11f`. Verified: the
merge is clean and its tree is **byte-identical** to
`eighth-review-tier1`, so one gate run on it covers the whole thing.

**The gate on that merge was started and KILLED mid-run at the author's
instruction (out of time). It has NEVER been green.** Treat `38eb11f` as
unverified. Before it goes anywhere:

```bash
cd ~/Downloads/cbt-main/harness && mvn21 clean verify   # expect >= 243
```

The full merge procedure the eighth session wrote is in that branch's
`PROJECT_STATE.md` §7.3 — including retiring the branches and the second
worktree AFTERWARDS, never before.

### 3.2 Open items owned by the author

- **G2 golden read-through** — still the author's, still blocking anything billed.
- **F69** — the exact host-sweep commands (destructive on a shared host;
  `iptables -F` would take out unrelated rules, so the harness must remove
  only its OWN). A commit on `eighth-review-tier1` (`c83efe3`) claims to
  address this — **read it before assuming the decision was made for you.**
- **F52 / F68** — an eighth-branch commit (`2d1e2c5`) claims the error-rate
  gate and the parity band. Same caveat: verify what was decided.
- **D12 / D13** — simulation spec format and confidence anchor, per
  `docs/SIMULATION_AND_RULES_ANALYSIS.md`.

### 3.3 Findings this session raised and did NOT fix

- **F68 — the parity gate is unstable.** `KafkaPerfTestParityTest` red-lined
  `clean verify` twice in one session in two different ways (ratio 0.22x
  against a 0.33x floor; then 11% errors from 5 s delivery timeouts), both
  at load average ~15, then passed at 1.86x in isolation on the same tree.
  It benchmarks a real broker, so it asserts on the laptop's spare
  capacity, and its floor is tighter than the 0.2x its own javadoc records.
- **F69 — host fault state outlives the process.** `tc qdisc del` /
  `iptables -D` / `pkill` exist ONLY inside `heal()`; `PRECLEAN_CMD` sweeps
  containers only. If the campaign JVM dies between inject and heal, the
  fault silently shapes every later run on that VM.

---

## 4. One honest warning about the build

A `mvn21 clean verify` in this session reported **BUILD SUCCESS having run
only 90 tests across 15 classes** instead of 177 across 34 — and the
skipped set included the very test covering that increment's fix. It did
not reproduce on an immediate re-run, and **no cause was found.** I did not
invent one.

Practical consequence, worth adopting: **read the test count from Maven's
own `Tests run:` summary line, not from arithmetic over
`target/surefire-reports/*.txt`.** A `verify` that can exit 0 having run
half the suite would undermine every "green gate" claim in this project's
history, so if a run ever finishes suspiciously fast, check the count
before trusting it.
