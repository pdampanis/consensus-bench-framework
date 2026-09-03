# How the two editions stay level

English is the master. The text is written in `content/en/*.tex` and
translated into `content/el/*.tex`. Nothing in this repository stops you
from editing the Greek directly — but if you do, `make sync` will not
know, and the two editions will start to disagree in ways nobody
notices until an examiner reads both.

## The mechanism, in one paragraph

Every paragraph-sized piece of text is a **block**, and every block
carries an id:

```latex
%%blk intro.problem.01
A distributed system consists of processes that are separated in space
and that communicate only by messages...
```

The Greek carries the same id **plus the hash of the English it was
translated from**:

```latex
%%blk intro.problem.01 from=91d794864574
Ένα κατανεμημένο σύστημα αποτελείται από διεργασίες που είναι
χωρισμένες στον χώρο...
```

Change one English word and the hash of that block changes. `make sync`
compares the stored hash against the current English and tells you which
Greek block is now behind. Nothing else changes; nothing breaks; the
build still works. You simply cannot *not know*.

## The three commands

```
make sync                                   what the Greek is behind on
python3 tools/sync.py --stamp --id BLOCKID  "I have translated this one"
python3 tools/sync.py --stamp               "I have translated all of them"
```

`--stamp` only writes hashes. It never touches the text. Run it **after**
you have updated the Greek, never before — a stamp on an untranslated
block is a lie that the tool will then repeat to you forever.

## What each state means

| the report says   | what happened                                        |
|-------------------|------------------------------------------------------|
| NOT TRANSLATED    | an English block with no Greek block of that id      |
| UNSTAMPED         | the Greek exists but says `from=PLACEHOLDER`         |
| STALE             | the English changed after the Greek was stamped      |
| ORPHANED          | a Greek block whose English block no longer exists   |
| OUT OF ORDER      | the same blocks, in a different order                |

Whitespace does not count. Re-wrapping an English paragraph does not
mark it stale; changing a word does.

## The other half: parity

`make parity` asks a different question. Not "is the Greek up to date"
but "do the two editions make the same **commitments**":

- the same blocks, in the same order (P01);
- the same citations, the same number of times (P02);
- the same `\grey` / `\prep` / `\unver` source markers (P03);
- the same labels and the same cross-references (P04);
- the same number of sections, figures and tables (P05, P06);
- the same measurement slots (P07).

A translation can be perfectly fluent and still quietly drop a citation.
P02 is the check that catches it.

## A worked example

You improve one English sentence in Chapter 2:

```
$ make sync
STALE — English moved on (1):
  ch2.tex:284  background.bounds.03 — English changed since translation
        EN: "Dolev and Reischuk prove that every deterministic..."
        EL: "Οι Dolev και Reischuk αποδεικνύουν ότι κάθε ντετερμινιστικό..."
```

You update the Greek block. Then:

```
$ python3 tools/sync.py --stamp --id background.bounds.03
stamped 1 block(s)
$ make sync
Greek is level with English: every block translated and stamped.
```

Then `make all` rebuilds all four documents and re-runs every check.

## Why this instead of a translation memory

A translation tool would try to keep the two texts identical. That is
the wrong goal: a Greek thesis is not an English thesis with the words
replaced, and the department's binding declaration exists only in Greek.
What matters is that **no difference is accidental**. This design makes
every difference either deliberate or visible, and it costs one line per
paragraph.
