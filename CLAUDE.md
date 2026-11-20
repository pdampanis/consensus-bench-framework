# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A master's thesis in one repo: a Java benchmark harness that drives seven
consensus systems (etcd, KRaft, Kafka+ZK, CometBFT, Paxos, EPaxos, HotStuff)
through one open-loop load model, and the bilingual LaTeX thesis in `thesis/`.

> If this checkout sits under `~/Downloads`, Claude Code also loads
> `~/Downloads/CLAUDE.md`. That file belongs to a DIFFERENT project (Release
> Note Generator), so ignore it. Older docs hard-code
> `~/Downloads/consensus-bench-thesis` as the repo path (e.g. `REPO=` in
> `docs/LOCAL_TESTING.md`). Use this checkout's root instead.

## Read first
`docs/PROJECT_STATE.md` is the single source of truth for where the
implementation stands. Its dated header names the next task. Backlog and
findings ledger (F-numbers, NEXT-n): `docs/PENDING_TASKS.md`. One-page map:
`docs/HOW_TO_CONTINUE.md`.

## Authority order (when sources conflict)
live code > IMPLEMENTATION_PLAN + DATA_ANALYSIS_METHODOLOGY >
PENDING_TASKS/PROJECT_STATE > other docs > docs/archive (history only).

## Working agreement (PROJECT_STATE §9, non-negotiable)
1. **Execute, don't assert.** If it can be compiled, run or parsed, do it and
   show the evidence before you say it works.
2. **TDD, strictly.** Failing test first, red for the right reason.
3. **One increment per session step.** Stop at checkpoints with
   done / evidence / not verified / next.
4. **Honest review.** Separate verified from assumed, every time.
5. **Respect gates G1/G2/G3.** The evidence must exist. G2's human
   read-through of the SSH goldens is the author's and is never skipped.
6. Update `docs/PROJECT_STATE.md` at session end.

## Hard safety rules
- **Never `terraform apply`** (G2 gate). `validate` and dummy-token `plan`
  are fine.
- Laptop numbers are functional evidence only, never thesis data
  (`environment=local` runs are excluded by `analyse.py`).
- The seven goldens in `harness/src/test/resources/goldens/` are the spec for
  `RemoteSshProvider`: written first, matched verbatim. They are FINAL text
  for the author's G2 read-through, so a change to any golden must be called
  out explicitly.

## Harness: build and test
```bash
cd harness
mvn21 clean verify                                        # full gate + shaded jar; the only "done" signal (~3-4 min)
mvn21 -Dtest='ArgParserTest' -DfailIfNoTests=false test   # one class
mvn21 -Dtest='KafkaDriverTest,KraftMultiBrokerFormationTest' test   # several
java -jar target/consensus-bench-0.1.0-SNAPSHOT.jar \
  local-run --size 3 --rate 100 --duration 6 --warmup 2 -v   # one-command local loop
```
- `mvn21` is a shell alias for `~/tools/maven/mvn21.sh` (Maven 3.9.11 with
  JAVA_HOME pinned to Corretto 21). Non-interactive shells do not have the
  alias, so call the script.
- The suite needs Docker and BOTH local images, built once per machine (they
  exist in no registry):
  `docker build -t paxi:6823d0b infra/paxi` and
  `docker build -t hotstuff:dc01ac8 infra/hotstuff`.
- Expected test count: `docs/LOCAL_TESTING.md` §1. Known laptop flake:
  `KafkaPerfTestParityTest` (N3). Rerun it on a quiet machine before you
  call it a regression.
- When a test fails, read `harness/target/surefire-reports/<Class>.txt` first.
  Every harness container is named `thesis-*`. Per-system test selections and
  debugging: `docs/PER_ALGORITHM_TEST_GUIDE.md`.
- Other checks: `python3 analysis/analyse.py --selftest`; Terraform
  validate/plan and promtool commands are in `docs/LOCAL_TESTING.md` §3–4.

## Harness architecture (`gr.thesis.bench`)
Diagram: `docs/PROJECT_STATE.md` §3. Per-system commit paths:
`docs/MEASUREMENT_DIAGRAMS.md`.
- `Main`: hand-rolled, fail-closed CLI. No subcommand = endpoint-run;
  `local-run`, `remote-run`, `campaign-run`.
- `core.WorkloadEngine`: open-loop schedule, bounded in-flight window.
  Latency is charged from the *intended* send time (coordinated-omission
  correction). Keys come from a fixed set of K=1000 with a conflict knob (D7).
  `LatencyRecorder` (HdrHistogram) and `EventLog` (failover timing).
- `driver.ConsensusDriver` SPI, one driver per system. A write completes ONLY
  on consensus commit. HotStuff has no driver: its upstream client runs and
  `HotStuffLogAnalyzer` (a port of upstream `logs.py`) parses the logs.
- `topology.ClusterProvider` SPI: `LocalDockerProvider` (Testcontainers) and
  `RemoteSshProvider` (sshj, command scripts pinned by the goldens), plus
  `SshFaultInjector`.
- `campaign`: `Inventory`, `RemoteRunner` (one cell on real VMs),
  `MatrixRunner` (one system block, seeded shuffle, manifest resume).
- `results.CsvResultsWriter`: writes
  `<system>/<scenario>/size<N>[/c<pct>]/<runId>/` with `throughput.csv`,
  `latency.csv`, `latency.hlog` and `manifest.json` (v2, honest `status`).
  `analysis/analyse.py` reads this tree.
- `validity.ValidityChecker`: the six per-run validity gates (methodology §4).
  Built as a library; not yet called per run (M5.4).

Invariants the tests pin: every system gets the same key stream and load
model (only the transport differs); a stall inflates the tail and never hides
it; a run that lost quorum or had mostly errors reports `failed`, never data.

Around the harness: `infra/` (Terraform `main.tf`, `cloud-init.yaml`, pinned
Dockerfiles for paxi and hotstuff), `observability/` (Prometheus + Grafana as
code, offline replay), `scripts/collect_block.sh`, `corpus/` (source papers).

## Thesis (`thesis/`)
```bash
cd thesis
make                # both PDFs (XeLaTeX via latexmk)
make docx           # both .docx files (needs the PDFs first)
make check          # every check; reports only, always exits 0
make check-strict   # a subset (sync, parity, diagrams, measurements, citations); stops at the first failure
make sync           # which Greek blocks are behind the English
make clean          # deletes the build files AND both PDFs
docker build -t thesis-tex docker && docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/thesis" thesis-tex make
```
- XeLaTeX only (fontspec; pdflatex fails). Never build with `-outdir`: the
  `\include`d chapters write `.aux` files into subfolders TeX cannot create.
  `make` prints no `file:line`; find errors with `grep -n -A1 '^!' thesis-en.log`.
  Editor and preview setup (VS Code + LaTeX Workshop, `latexmk -pvc`, Docker):
  `thesis/build-guide.html`.
- English (`content/en/`) is the master; Greek (`content/el/`) follows. Text
  is in `%%blk <id>` blocks, and each Greek block stores `from=<hash>` of its
  English source. Edit the English first. After you translate a block, run
  `python3 tools/sync.py --stamp --id <BLOCKID>`. A new Greek block starts as
  `%%blk <id> from=PLACEHOLDER`; `python3 tools/sync.py --new` lists untranslated
  ids. A new chapter needs an `\include` at the same place in `main-en.tex` and
  `main-el.tex` (`make check` compares the page orders). Details: `docs/SYNC.md`.
- No number that was not measured: declare it in `data/measurements.tex` and
  use `\meas{key}` (prints [PENDING] until filled). An unverified source is
  marked `\unver` at every use; `cite-check.py` enforces this.
- PDFs, LaTeX by-products and `docx/build/` are gitignored. Do not commit them.
  Exception: the VS Code preview writes `main-en.pdf` / `main-el.pdf`, which are
  not ignored.
- Writing guidance for thesis prose is in `thesis/skills/*/SKILL.md` (English
  STE, plain technical Greek, LaTeX and references, explanation, diagrams).
  Claude Code does not load these automatically; read the relevant one first.

## Study pages (`docs/study/`)
`phase-a.html` (theory) and `phase-b.html` (theory mapped onto the code) are
built single-file pages. Do not edit the HTML: the sources and build scripts live
outside the repo, in `~/Downloads/consensus-sites-2026-11-18/` (`*/src/content.json`,
`*/build/build.py`). Phase B's code links are pinned to commit 121cf05, so a code
change can make its statements stale.
