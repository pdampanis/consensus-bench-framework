# =====================================================================
#  One source tree, four documents.
#
#      make            both PDFs
#      make docx       both .docx  (needs the PDFs first — it reads their
#                                   .aux and .bbl for the numbering)
#      make all        all four, then every check
#      make check      every check, no building
#
#  English is the master. Greek follows it. `make sync` says how far
#  behind the Greek is; `make check` fails while any block is stale.
# =====================================================================

LANGS   = en el
PDFS    = $(addprefix thesis-,$(addsuffix .pdf,$(LANGS)))
SOURCES = style/*.tex figures/src/*.tex figures/labels-*.tex \
          frontmatter/meta.tex data/*.tex bib/references.bib

.PHONY: all pdf docx check check-strict sync data diagrams parity \
        prose docx-check docx-selftest clean distclean help

pdf: $(PDFS)

all: pdf docx check

# --- PDF --------------------------------------------------------------
# latexmk runs xelatex and bibtex as many times as the cross-references
# need. -halt-on-error would stop at the first problem; nonstopmode gets
# a full log instead, which is what you want when a chapter is broken.
thesis-en.pdf: main-en.tex content/en/*.tex frontmatter/en/*.tex $(SOURCES)
	@latexmk -xelatex -interaction=nonstopmode -jobname=thesis-en main-en.tex

thesis-el.pdf: main-el.tex content/el/*.tex frontmatter/el/*.tex $(SOURCES)
	@latexmk -xelatex -interaction=nonstopmode -jobname=thesis-el main-el.tex

# --- Word -------------------------------------------------------------
docx: $(PDFS)
	@bash docx/build.sh en
	@bash docx/build.sh el

docx-check:
	@python3 docx/verify.py en
	@python3 docx/verify.py el

# Deliberately breaks each .docx and requires the matching check to
# notice. A check that cannot fail is worse than no check.
docx-selftest:
	@python3 docx/selftest.py en
	@python3 docx/selftest.py el

# --- Checks -----------------------------------------------------------
# None of these stop the build on their own; `make check` runs them all
# and reports. `make check-strict` is the one that fails.
check:
	@echo "=== Greek follows English ==="
	@python3 tools/sync.py || true
	@echo "\n=== the two editions make the same commitments ==="
	@python3 tools/parity.py || true
	@echo "\n=== one diagram standard ==="
	@python3 tools/diagram-check.py || true
	@echo "\n=== your own measurements ==="
	@python3 tools/data-check.py || true
	@echo "\n=== LaTeX mechanics ==="
	@python3 tools/latex-check.py content/en/*.tex frontmatter/en/*.tex || true
	@python3 tools/latex-check.py content/el/*.tex frontmatter/el/*.tex || true
	@echo "\n=== citations and bibliography ==="
	@python3 tools/cite-check.py content/*/*.tex --bib bib/references.bib || true
	@echo "\n=== explanation structure ==="
	@python3 tools/explain-check.py content/*/*.tex || true
	@echo "\n=== English prose (Simplified Technical English) ==="
	@python3 tools/ste-check.py content/en/*.tex --mode flavored || true
	@echo "\n=== Greek prose (the mirror of the above) ==="
	@python3 tools/ate-check.py content/el/*.tex --mode flavored || true

check-strict:
	@python3 tools/sync.py
	@python3 tools/parity.py
	@python3 tools/diagram-check.py
	@python3 tools/data-check.py

sync:
	@python3 tools/sync.py

parity:
	@python3 tools/parity.py

diagrams:
	@python3 tools/diagram-check.py

prose:
	@python3 tools/ste-check.py content/en/*.tex --mode flavored || true
	@python3 tools/ate-check.py content/el/*.tex --mode flavored || true
	@python3 tools/explain-check.py content/*/*.tex || true

data:
	@python3 tools/data-check.py

# --- Housekeeping -----------------------------------------------------
clean:
	@latexmk -C -jobname=thesis-en main-en.tex >/dev/null 2>&1 || true
	@latexmk -C -jobname=thesis-el main-el.tex >/dev/null 2>&1 || true
	@rm -f thesis-*.bbl thesis-*.blg thesis-*.out thesis-*.run.xml
	@rm -rf figures/out/* docx/build/figtex docx/build/toc

distclean: clean
	@rm -rf docx/build

help:
	@echo "make          both PDFs"
	@echo "make docx     both .docx (run make first)"
	@echo "make all      everything, then every check"
	@echo "make check    every check, nothing built"
	@echo "make sync     how far behind the Greek is"
	@echo "make data     which of your measurements are still missing"
