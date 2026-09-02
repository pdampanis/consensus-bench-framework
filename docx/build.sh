#!/bin/bash
# Builds docx/build/thesis-<lang>.docx from the SAME sources as the PDF.
#
#   bash docx/build.sh en          full build + checks
#   bash docx/build.sh el --fast   skip re-rendering the figures
#
# Prerequisite: "make" must have run in the root, so that the .aux files
# and thesis-<lang>.bbl exist — that is where the numbers of the
# sections, figures, tables and references come from.
set -e
cd "$(dirname "$0")"

LANG_ARG="${1:-en}"
if [ "$LANG_ARG" != "en" ] && [ "$LANG_ARG" != "el" ]; then
  echo "usage: bash docx/build.sh {en|el} [--fast]" >&2
  exit 2
fi
shift || true

if [ ! -f "../thesis-$LANG_ARG.bbl" ]; then
  echo "missing ../thesis-$LANG_ARG.bbl — run \"make\" in the root first" >&2
  exit 1
fi

echo "== [$LANG_ARG] 1/6 department styles =="
python3 make-reference.py "$LANG_ARG"

if [ "$1" != "--fast" ]; then
  echo "== [$LANG_ARG] 2/6 figures -> PNG =="
  python3 render-figures.py "$LANG_ARG"
else
  echo "== [$LANG_ARG] 2/6 figures: skipped (--fast) =="
fi

echo "== [$LANG_ARG] 3/6 LaTeX -> intermediate =="
python3 tex2pandoc.py "$LANG_ARG"

echo "== [$LANG_ARG] 4/6 pandoc =="
pandoc "build/body-$LANG_ARG.tex" -f latex -t docx \
  --reference-doc="build/reference-$LANG_ARG.docx" \
  -o "build/body-$LANG_ARG.docx"

echo "== [$LANG_ARG] 5/6 assembling =="
python3 assemble.py "$LANG_ARG"

echo "== [$LANG_ARG] 6/6 contents and lists =="
# Renders the file, reads the real page numbers and writes them into the
# fields. Without this the document opens with an empty table of contents
# until someone presses F9. Needs LibreOffice; skipped if absent.
python3 fill_toc.py "$LANG_ARG" \
  || echo "  (no LibreOffice — press Ctrl+A then F9 in Word)"

echo
echo "== [$LANG_ARG] checking the .docx against the LaTeX =="
python3 verify.py "$LANG_ARG"
