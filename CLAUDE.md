# CLAUDE.md — consensus-bench-thesis

> This repo lives under `~/Downloads`, whose CLAUDE.md belongs to a
> DIFFERENT project (Release Note Generator) — ignore that file here.
> This project's real rules live in the docs; this file only points at them.

## Read first
`docs/PROJECT_STATE.md` — the single source of truth for where the
implementation stands. Backlog + findings ledger: `docs/PENDING_TASKS.md`.

## Authority order (when sources conflict)
live code > IMPLEMENTATION_PLAN + DATA_ANALYSIS_METHODOLOGY >
PENDING_TASKS/PROJECT_STATE > other docs > docs/archive (history only).

## Working agreement (PROJECT_STATE §9 — non-negotiable)
1. **Execute, don't assert** — if it can be compiled/run/parsed, do it and
   show the evidence before claiming it works.
2. **TDD, strictly** — failing test first, red for the right reason.
3. **One increment per session-step**; stop at checkpoints with
   done / evidence / not-verified / next.
4. **Honest review** — separate verified from assumed, every time.
5. **Respect gates G1/G2/G3** — the evidence must exist; G2's human
   read-through of the SSH goldens is the author's, never skipped.

## Hard safety rules
- **Never `terraform apply`** (G2 gate). `validate`/dummy-token `plan` are fine.
- Laptop numbers are functional evidence only — never thesis data.

## Build / test
```bash
cd harness && mvn21 clean verify   # ~/tools/maven/mvn21.sh if no alias;
                                   # needs Docker + once-per-machine:
                                   # docker build -t paxi:6823d0b infra/paxi
```
