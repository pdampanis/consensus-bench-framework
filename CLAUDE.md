# CLAUDE.md — consensus-bench-thesis

> This repo lives under `~/Downloads`, whose CLAUDE.md belongs to a
> DIFFERENT project (Release Note Generator) — **ignore that file here.**
> That is this file's main job; the rest is a pointer.

## Orient first (30 seconds, before reading anything)

The docs describe the repo; **git is the repo.** Run this before trusting any
table, including the ones in PROJECT_STATE:

```bash
git worktree list                       # which checkouts exist, on which branches
git branch -vv --all                    # every branch, local and remote
git status --short                      # uncommitted work HERE
git log --oneline origin/main..HEAD     # what this branch has that main lacks
for b in $(git for-each-ref --format='%(refname:short)' refs/heads); do
  printf '%-28s %s ahead of main\n' "$b" "$(git rev-list --count origin/main..$b)"
done                                    # unmerged work on OTHER branches
```

Work on this project has lived on **more than one branch and worktree at
once**, so "am I looking at all of it?" is a real question with a cheap
answer. If what you find disagrees with a doc, **git wins** — and fix the
doc, because a stale coordination table is worse than none.

## Read first, and only this

**`docs/PROJECT_STATE.md` is the SINGLE DRIVING DOCUMENT.** Decisions, open
issues, the prioritized queue, and the merge plan all live there. Start every
session by reading it and taking the top unblocked item from its §6.

Everything else in `docs/` is **reference** — consulted when PROJECT_STATE
sends you there, never planned from. `docs/archive/` is history, including
the full F1–F76 findings ledger (code comments across the harness cite those
F-numbers, so it stays).

## Authority order (when sources conflict)

```
live code  >  IMPLEMENTATION_PLAN + DATA_ANALYSIS_METHODOLOGY
           >  PROJECT_STATE  >  other docs  >  docs/archive
```

PROJECT_STATE wins on **what to do next**; the plan and the methodology win
on **how it must be done** (acceptance criteria, statistics, validity).

## The loop (PROJECT_STATE §3 and §9 — non-negotiable)

1. **Execute, don't assert** — if it can be compiled, run or parsed, do it
   and show the evidence before claiming it works.
2. **TDD, strictly** — failing test first, watched red for the RIGHT reason.
3. **One increment per session-step**; stop with done / evidence /
   not-verified / next.
4. **Honest review** — separate verified from assumed, every time; name what
   was not tested.
5. **Respect gates G1/G2/G3** — the evidence must exist. G2's human
   read-through of the goldens is the author's and is never skipped.
6. **Update PROJECT_STATE at session end** — it is the only thing a fresh
   session reads first.

## Hard safety rules

- **Never `terraform apply`** (G2 gate). `validate` / dummy-token `plan` are
  fine.
- **Laptop numbers are functional evidence only — never thesis data.**
- **Never widen a gate to make a build pass.** If a gate is wrong, say so,
  argue it from measured evidence, and change it deliberately (F68 is the
  worked example).

## Build / test

```bash
cd harness && mvn21 clean verify   # ~/tools/maven/mvn21.sh if no alias
# Needs Docker + BOTH local images, once per machine:
#   docker build -t paxi:6823d0b infra/paxi
#   docker build -t hotstuff:dc01ac8 infra/hotstuff
```
**Read the count from Maven's own `Tests run:` summary line**, never from
arithmetic over report files, and treat a suspiciously fast run as a FAILED
gate until the count is confirmed — see F76 in PROJECT_STATE §5.
Current baseline: **243 tests**, ~5–7 min.

## Branches and worktrees

Work happens on a named branch off `main` (`<review>-tier<N>`). `main` is the
integration branch; **there is no `master`.** Push the branch early — there
is no CI and no backup.

**One active session per branch.** Two sessions sharing one checkout lose
each other's uncommitted edits silently — not as a merge conflict, but as a
whole-file overwrite. A second concurrent session gets its own worktree:
`git worktree add ../cbt-<topic> -b <topic>`.

**Which work is on which branch is recorded in `PROJECT_STATE.md` §3** — but
that table is a SNAPSHOT, so verify it with the orientation commands above
and correct it when it drifts. **If you end a session mid-increment, say so
in §3**: what is in flight, on which branch and worktree, and what the next
step was. The alternative is the next session re-deriving it from commit
messages, or worse, redoing it.

The 9-item merge gate is in `docs/GIT_WORKFLOW.md`; the step-by-step runbook
to `main` is `docs/PROJECT_STATE.md` §7.

## Next task

See `docs/PROJECT_STATE.md` §6. As of 2026-08-15 the top three are the
author's: **G2 golden read-through** (blocks everything billed — three
goldens changed and need re-reading), then the P3.5 price check, then the
P3.4 canary.
