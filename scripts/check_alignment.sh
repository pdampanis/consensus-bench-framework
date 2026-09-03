#!/usr/bin/env bash
# check_alignment.sh — the thesis and the benchmark are one project.
#
# Run this after ANY change to harness/, analysis/ or corpus/. It answers
# the three questions that go stale silently:
#
#   1. corpus/ vs thesis/bib   — does every VERIFIED-PDF claim still have
#                                the file it was verified from?
#   2. thesis/data             — which sentences are still waiting for a
#                                number the harness has not produced?
#   3. thesis/content          — do the two editions still say the same
#                                thing, and are the citations still sound?
#
# It REPORTS and exits 0 unless something is actually broken (a corpus
# file changed content, a manifest row lost its file). Use --strict in CI.
#
# The PDF build (`cd thesis && make`) needs xelatex/latexmk/pandoc, which
# this script does NOT require — every check below is pure Python.
set -uo pipefail
cd "$(dirname "$0")/.."
STRICT=${1:-}
rc=0

echo "=== 1. corpus vs thesis/bib ==============================="
python3 scripts/corpus.py verify || rc=1

echo
echo "=== 2. numbers the thesis is still waiting for ============"
python3 thesis/tools/data-check.py || true

echo
echo "=== 3. the two editions, and the citations ================"
python3 thesis/tools/parity.py    || rc=1
python3 thesis/tools/mainparity.py || rc=1
python3 thesis/tools/sync.py      || true
python3 thesis/tools/cite-check.py thesis/content/*/*.tex \
        --bib thesis/bib/references.bib || true

echo
echo "=== 4. what only a person can check ======================="
cat <<'EOT'
If this change altered WHAT the harness measures or HOW it measures it —
a metric, a scenario, a cluster size, a validity gate, a default — then
re-read, in this order:

  thesis/data/measurements.tex   does every \meas{} key still name a
                                 quantity the harness actually produces?
  thesis/content/en/ch1.tex §1.4 do the research questions still match
                                 what the campaign will answer?
  thesis/content/*/ch3+          the method chapters, once they exist

A code change that silently invalidates a sentence in the thesis is the
failure this file exists to prevent. The checks above cannot see it.
EOT

[ "$STRICT" = "--strict" ] && exit $rc
exit 0
