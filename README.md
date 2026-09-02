# Evolution and Comparison of Consensus Protocols in Distributed Systems

Diploma thesis · Department of Informatics and Computer Engineering,
University of West Attica · Panagiotis Dampanis (711070095) ·
Supervisor: Prof. Grammati Pantziou

One source tree produces **four documents**:

| file | what it is |
|------|------------|
| `thesis-en.pdf`  | the English edition, typeset with XeLaTeX |
| `thesis-el.pdf`  | the Greek edition, same source tree |
| `docx/build/thesis-en.docx` | the English edition as a real Word file |
| `docx/build/thesis-el.docx` | the Greek edition as a real Word file |

English is the master language. Greek follows it, and a tool says how
far behind it is (see `docs/SYNC.md`).

## Build

```
make            both PDFs
make docx       both .docx files (run make first)
make all        all four, then every check
make check      every check, nothing built
```

Needs: XeLaTeX with `polyglossia`, `latexmk`, `pandoc`, `python-docx`,
`docxcompose`, `lxml`, and LibreOffice (`soffice`) for filling the table
of contents. Times New Roman if you have it; the build falls back to the
metric-compatible Liberation Serif and looks the same at the millimetre.

## Why the .docx exists

The department accepts a Word file. Producing one from LaTeX usually
means a document where every number — "1.2", "Figure 2.3", "[14]" — is
typed text, so the first hand edit silently breaks the rest.

This one is not that. In `docx/build/thesis-*.docx`:

- headings are numbered by a Word multilevel list bound to Heading 1/2/3;
- captions are `SEQ` fields that restart in each chapter;
- cross-references are `REF` fields pointing at bookmarks;
- the table of contents and both lists are `TOC` fields;
- every field carries the number LaTeX computed as its **cached result**,
  so the document reads correctly before anyone presses F9.

Insert a section by hand in Word and everything after it renumbers
itself. That is what the Guidelines ask for, and it is checked (V18).

## The layout

```
main-en.tex  main-el.tex   one per edition; the page order lives here
content/en/  content/el/   the chapters. English is written first
frontmatter/               meta.tex + the preliminary pages, per edition
figures/src/               the figures. Geometry only — no text
figures/labels-*.tex       the text of the figures, one file per language
style/                     the class setup and THE diagram standard
data/                      measurements register, abbreviations
bib/references.bib         every entry carries how it was verified
tools/                     the checks (see below)
docx/                      the LaTeX -> Word pipeline
docs/SYNC.md               how the two editions stay level
```

## The checks

`make check` runs all of them. None of them is decorative; each one
exists because something went wrong once.

| tool | question it answers |
|------|--------------------|
| `sync.py`         | which Greek blocks are behind the English |
| `parity.py`       | do the two editions make the same commitments |
| `diagram-check.py`| is there still exactly one diagram standard |
| `data-check.py`   | which of your measurements are still missing |
| `latex-check.py`  | LaTeX mechanics, per edition |
| `cite-check.py`   | citations, and whether unverified ones are marked |
| `explain-check.py`| does the structure make explanation possible |
| `ste-check.py`    | English prose against Simplified Technical English |
| `ate-check.py`    | the Greek mirror of the above |
| `docx/verify.py`  | 20 checks: does the .docx say the same as the PDF |
| `docx/selftest.py`| damages the .docx 11 ways; each check must catch its own |

That last one matters more than it looks. A check that cannot fail is
worse than no check, because it produces confidence instead of
information. Three of these checks were once exactly that, and the
self-test is what found them.

## Two rules this thesis follows

**No number that was not measured.** A figure that does not exist yet
cannot be typed into the text at all: it is declared in
`data/measurements.tex` and used as `\meas{key}`, which prints
**[PENDING]** until the value is filled in. `make data` lists what is
still outstanding, grouped by the research question that needs it. See
`docs/MEASUREMENTS.md`.

**Nothing unsourced.** Every bibliography entry records how its data
were verified: from the article, from an index through the DOI, or not
at all. A source whose data could not be verified is marked `\unver` at
**every point where it is used**, and `cite-check.py` fails if one is
not. `\grey` marks grey literature and `\prep` marks preprints, the same
way.

## Status

Chapters 1 and 2 are written in both languages. The experimental
chapters are not, and the abstract deliberately still carries a marked
placeholder where the findings go. `make data` is the list of what the
benchmark campaign has to produce before that placeholder can be
replaced.
