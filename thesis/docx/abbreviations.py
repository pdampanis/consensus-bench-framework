#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""The abbreviations list, read from data/abbreviations.tex.

One source for four documents. The .tex file holds all three columns —
short form, English expansion, Greek expansion — plus a fourth field
recording where the expansion comes from. LaTeX picks a column by
redefining \\abbrentry; here the column is picked by language.

The provenance field is never printed. It exists so that project rule R1
("nothing unsourced") can be checked on a list that otherwise looks like
common knowledge.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import texparse as T

SRC = os.path.join(T.ROOT, 'data', 'abbreviations.tex')


def entries():
    """[(short, expansion in the current language, provenance)]."""
    src = T.strip_comments(open(SRC, encoding='utf-8').read())
    out = []
    for m in re.finditer(r'\\abbrentry\s*(?=\{)', src):
        i = m.end()
        args = []
        for _ in range(4):
            a, i = T.match_brace(src, i)
            a = ' '.join(a.split())
            # TeX dashes, as everywhere else in the pipeline.
            args.append(a.replace('---', '—').replace('--', '–'))
            while i < len(src) and src[i] in ' \n\t':
                i += 1
        short, en, el, why = args
        out.append((short, en if config.LANG == 'en' else el, why))
    return out


def pending():
    """The abbreviations deliberately kept OUT of the list, with no
    expansion that traces to a source we hold."""
    src = T.strip_comments(open(SRC, encoding='utf-8').read())
    m = re.search(r'\\newcommand\{\\abbrpending\}\{([^{}]*)\}', src)
    return [x.strip() for x in m.group(1).split(',')] if m else []


if __name__ == '__main__':
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    for s, long_, why in entries():
        print('%-6s %-58s  [%s]' % (s, long_[:58], why[:40]))
    print('pending (not in the list): %s' % ', '.join(pending()))
