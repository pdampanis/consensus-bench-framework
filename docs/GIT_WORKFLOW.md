# Git Workflow — Consensus Bench Framework

Repo: `https://github.com/pdampanis/consensus-bench-framework.git`
Local: `/home/pdampani/Downloads/consensus-bench-thesis`

## Current: HTTPS (active)

Authenticated via GitHub CLI (`gh`) credential helper.
No SSH keys were created or modified for this repo.

```
# git config credential.https://github.com.helper
!/usr/bin/gh auth git-credential
```

The helper uses your existing `gh auth` session (logged in as `pdampanis`).
Your OpenBet `id_rsa` key and `~/.ssh/config` were left untouched.

```bash
cd /home/pdampani/Downloads/consensus-bench-thesis

# Stage & commit
git add .
git commit -m "your message"

# Push
git push origin main

# Pull
git pull origin main
```

## Future: SSH (planned)
See [SSH_SETUP.md](SSH_SETUP.md) for switch instructions.

---

## Before anything: find out what exists

This repo has carried work on **two branches in two worktrees at the same
time**. Any doc that lists them is a snapshot; these commands are the truth:

```bash
git worktree list                     # checkouts and their branches
git branch -vv --all                  # every branch, local and remote
for b in $(git for-each-ref --format='%(refname:short)' refs/heads); do
  printf '%-28s %s ahead of main\n' "$b" "$(git rev-list --count origin/main..$b)"
done                                  # unmerged work on OTHER branches
```

The last one is the important one: it answers "is there work I am not looking
at?", which is how the seventh/eighth containment relationship was
established in the first place. `PROJECT_STATE.md` §3 records the current
answer and says plainly that git overrides it.

## Branches — what we actually use

**There is no `master`.** The integration branch is **`main`** (also
`origin/main`, the only remote branch).

Observed convention, in use since the fifth review: work happens on a
**named review/increment branch** cut from `main`, e.g.
`seventh-review-tier1`. One branch per review pass or per work theme; the
increments inside it are separate commits, one per TDD increment, each with
its own green gate. `main` is only ever fast-forwarded/merged from a branch
that has passed the gate below.

```bash
git checkout main && git pull origin main
git checkout -b <review>-tier<N>          # e.g. eighth-review-tier1
# … increments, one commit each …
git push -u origin <review>-tier<N>       # DO THIS EARLY, see below
```

**Push the branch as soon as it has one commit.** As of 2026-08-14 the
`seventh-review-tier1` branch carried **five commits with no upstream** —
several hours of TDD work existing on one laptop only. There is no CI and no
backup; a disk failure loses it. Pushing costs nothing and is not a claim
that the work is finished.

---

## The full gate before merging to `main`

There is **no CI in this repo** (`.github/workflows` does not exist), so
every item below is run by a human or an agent and the evidence is written
down. That is the whole gate — nothing else is checking.

| # | Gate | How it is satisfied | Why it exists |
|---|------|--------------------|---------------|
| 1 | **Suite green, count verified** | `cd harness && mvn21 clean verify` → BUILD SUCCESS, and the count is read from **Maven's own `Tests run:` summary line**, not from arithmetic over report files. It must **match or exceed** the count in `PROJECT_STATE`. Current baseline: **177 tests / 34 classes** | F76: a `verify` once reported BUILD SUCCESS having run only **90 tests across 15 classes**, skipping the test covering the change being gated. A build that exits 0 having run half the suite undercuts every green-gate claim in the project's history |
| 2 | **The environment could actually run the tests** | Docker daemon up, and BOTH local images present: `docker images \| grep -E 'paxi:6823d0b\|hotstuff:dc01ac8'`. A ~5 min wall-clock for `verify` is the sanity check — a suspiciously fast green is gate 1's failure mode | Integration tests need the daemon; the two source-built images exist in no registry (F33) |
| 3 | **Every finding touched is ledgered** | `PROJECT_STATE.md` carries each F-number with status (OPEN/CLOSED/DECIDED) **and its evidence** — the measurement, not the assertion | The ledger is the handoff contract; a finding that lives only in a session's scrollback is lost work (this gate exists *because* F76 was found that way) |
| 4 | **`PROJECT_STATE.md` header updated** | The header says what changed, what was verified by execution, and what is next | Working agreement §9 rule 6. It is the first thing a fresh session reads |
| 5 | **TDD evidence stated** | Each behaviour-changing commit message names the failing test and that it was seen **red for the right reason** | Working agreement §9 rule 2 |
| 6 | **Goldens: re-read if touched** | If any file under `src/test/resources/goldens/` changed, the **G2 human read-through is redone for the changed blocks** and signed off in `PROJECT_STATE` | The seven goldens are FINAL text for G2; changing them after sign-off silently invalidates it |
| 7 | **No `terraform apply` was run** | `hcloud server list` empty / no state change in `infra/` | Hard safety rule — G2 gate, and a forgotten cluster costs ~€7/day |
| 8 | **Doc authority sweep** | If the branch changed behaviour a doc describes, that doc changed in the **same branch** | Authority order is `live code > plan/methodology > state docs`; drift is how the ledger fills up |
| 9 | **Branch pushed** | `git push -u origin <branch>` | Reviewability and backup |

Merge only when 1–9 hold:

```bash
git checkout main && git merge --no-ff <review>-tier<N> && git push origin main
```

`--no-ff` keeps the increment series visible as a unit — the review pass is
the meaningful history, not the individual commits.

---

## Concurrent sessions — the hazard and the protocol

Multiple Claude sessions may be open on this repo at once. **They share one
working tree** (`git worktree list` shows a single checkout), so they are not
isolated by git in any way.

The failure mode is **not** a merge conflict — git never gets the chance. It
is a **silent lost update**: session A has a file's older content in its
context, writes the whole file, and session B's uncommitted edits to the same
file vanish with no error and no diff to notice. The highest-risk files are
the ones every session touches: `PROJECT_STATE.md` and `PROJECT_STATE.md`.

Protocol:

1. **Commit early and often.** An uncommitted edit is the only thing that can
   be silently lost. Committed work can always be recovered from reflog.
2. **One active session per branch at a time.** If a second session is needed
   concurrently, give it its own worktree:
   `git worktree add ../cbt-<topic> -b <topic>` — then the trees are
   genuinely isolated and git handles the merge.
3. **Before editing a shared doc, re-read it.** Do not edit from a copy held
   in context from earlier in the session; another session may have rewritten
   it since.
4. **Prefer targeted edits over whole-file writes** on `PROJECT_STATE.md` and
   `PROJECT_STATE.md`. An anchored edit fails loudly when the content moved;
   a whole-file write succeeds and destroys.
5. **Record who is doing what** in the `PROJECT_STATE` header when a session
   ends mid-increment, so the next session (or the other one) knows what is
   in flight.
