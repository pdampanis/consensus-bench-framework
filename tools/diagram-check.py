#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Enforces the one diagram standard, so figures cannot drift apart.

A diagram standard that lives only in a style file lasts about three
figures. The fourth one needs a slightly different colour, sets it
inline, and from then on the thesis has two visual languages. These
checks make that impossible to do by accident.

  G01  a colour defined or used outside the palette
  G02  a figure that is neither dgdata/dgbarchart nor dgflow
  G03  a width, height, font size or line width set inside a figure
       (the standard owns those; setting them locally is how two figures
       end up different sizes)
  G04  a \\fl{} label key used in a figure but missing from one of the
       two dictionaries — the figure would render blank in that edition
  G05  a label key defined but never used. A NOTE, not a failure: a key
       added ahead of the figure that will use it is normal while the
       thesis is being written
  G06  the two dictionaries do not define the same key set
  G07  Greek text inside a figure source (figures are language-neutral;
       text belongs in the two dictionaries)

    python3 tools/diagram-check.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'figures', 'src')
LAB = {'en': os.path.join(ROOT, 'figures', 'labels-en.tex'),
       'el': os.path.join(ROOT, 'figures', 'labels-el.tex')}
CONTENT = os.path.join(ROOT, 'content')

PALETTE = {'dgCFT', 'dgBFT', 'dgAux', 'dgSand', 'dgPurple', 'dgGrey',
           'dgInk', 'dgPaper'}
ENVS = {'dgdata', 'dgbarchart', 'dgflow'}



def strip_comments(s):
    out = []
    for line in s.split('\n'):
        res, esc = [], False
        for ch in line:
            if esc:
                res.append(ch); esc = False
            elif ch == '\\':
                res.append(ch); esc = True
            elif ch == '%':
                break
            else:
                res.append(ch)
        out.append(''.join(res))
    return '\n'.join(out)


def figures():
    if not os.path.isdir(SRC):
        return []
    return sorted(f for f in os.listdir(SRC) if f.endswith('.tex'))


def label_keys(path):
    if not os.path.exists(path):
        return {}
    src = strip_comments(open(path, encoding='utf-8').read())
    return {m.group(1): m.group(2)
            for m in re.finditer(r'\\deflabel\{([^}]*)\}\{((?:[^{}]|\{[^{}]*\})*)\}',
                                 src)}


def main():
    bad, notes = [], []
    figs = figures()
    if not figs:
        print('no figures in figures/src')
        return 0

    en, el = label_keys(LAB['en']), label_keys(LAB['el'])
    used = {}

    for name in figs:
        path = os.path.join(SRC, name)
        raw = open(path, encoding='utf-8').read()
        src = strip_comments(raw)

        # G01 — colours
        for m in re.finditer(r'\\definecolor\{([^}]*)\}', src):
            bad.append('G01 %s defines colour %s — the palette lives in '
                       'style/diagrams.tex' % (name, m.group(1)))
        # A colour NAME used where a palette name belongs. The earlier
        # version looked for the shape c[A-Z]..., which no palette name
        # has — so its "not in the palette" test was true for everything
        # it matched and false for everything it did not. It caught
        # neither `draw=red` nor `draw=dgRed`.
        for m in re.finditer(r'(?:draw|fill|text|color)\s*=\s*'
                             r'([A-Za-z][A-Za-z0-9]*)', src):
            base = m.group(1)
            if base in PALETTE or base in ('none', 'white', 'black'):
                continue
            bad.append('G01 %s uses colour "%s" — the palette is %s, and it '
                       'lives in style/diagrams.tex'
                       % (name, base, ', '.join(sorted(PALETTE))))

        # G02 — the two kinds, and nothing else
        envs = set(re.findall(r'\\begin\{(\w+)\}', src))
        raw_env = envs & {'tikzpicture', 'axis', 'groupplot', 'semilogyaxis',
                          'loglogaxis'}
        if raw_env:
            bad.append('G02 %s opens %s directly — use dgdata, dgbarchart '
                       'or dgflow' % (name, ', '.join(sorted(raw_env))))
        if not (envs & ENVS):
            bad.append('G02 %s uses none of dgdata/dgbarchart/dgflow' % name)

        # G03 — geometry and type belong to the standard
        # "line width=" must not be read as "width=", and a relative
        # width (0.8\textwidth) is the ordinary way to set one, so it has
        # to be caught too.
        for pat, what in ((r'(?<!line )\bwidth\s*=\s*[\d.]+\s*'
                           r'(cm|mm|pt|in|\\[a-zA-Z]+)', 'width'),
                          (r'\bheight\s*=\s*[\d.]+\s*'
                           r'(cm|mm|pt|in|\\[a-zA-Z]+)', 'height'),
                          (r'font\s*=\s*\\(tiny|scriptsize|footnotesize|small|'
                           r'normalsize|large|Large)\b', 'font size'),
                          (r'line width\s*=\s*[\d.]+', 'line width')):
            for m in re.finditer(pat, src):
                bad.append('G03 %s sets %s (%s) — the standard sets it'
                           % (name, what, m.group(0)[:34]))

        # G04/G07 — text
        keys = re.findall(r'\\fl\{([^}]*)\}', src)
        for k in keys:
            used.setdefault(k, []).append(name)
        # G07: the unambiguous version of "no text in figures". Trying to
        # spot English prose here produced nothing but false positives —
        # protocol names, style keys and option values all look like
        # words. Greek letters cannot be any of those, so a Greek
        # character in a shared figure is exactly the failure this check
        # is for: text that will be wrong in the English edition.
        for m in re.finditer(r'[\u0386-\u03ce\u1f00-\u1ffe]+', src):
            bad.append('G07 %s contains Greek text "%s" — figures are '
                       'language-neutral; move it to a \\fl{} key'
                       % (name, m.group(0)[:30]))

    for k, where in sorted(used.items()):
        for lang, d in (('en', en), ('el', el)):
            if k not in d:
                bad.append('G04 label key "%s" (used in %s) is missing from '
                           'figures/labels-%s.tex'
                           % (k, ', '.join(where), lang))

    for k in sorted(set(en) | set(el)):
        if k not in used:
            notes.append('G05 label key "%s" is defined but never used' % k)
    only_en = sorted(set(en) - set(el))
    only_el = sorted(set(el) - set(en))
    if only_en or only_el:
        bad.append('G06 the dictionaries disagree — only EN: %s | only EL: %s'
                   % (only_en[:6], only_el[:6]))

    # figures must be shared: content/ must not hold a picture of its own
    for lang in ('en', 'el'):
        d = os.path.join(CONTENT, lang)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith('.tex'):
                continue
            s = strip_comments(open(os.path.join(d, name),
                                    encoding='utf-8').read())
            for env in ('tikzpicture', 'axis', 'groupplot', 'semilogyaxis',
                        'loglogaxis', 'dgdata', 'dgbarchart', 'dgflow'):
                if '\\begin{%s}' % env in s:
                    bad.append('G02 content/%s/%s draws a figure inline — '
                               'figures live in figures/src so both editions '
                               'share one geometry' % (lang, name))

    print('figures: %d   label keys: %d (EN %d, EL %d)   uses: %d'
          % (len(figs), len(set(en) | set(el)), len(en), len(el), len(used)))
    for n in notes:
        print('  note  ' + n)
    for b in bad:
        print('  FAIL  ' + b)
    if not bad:
        print('one standard, %d figures, both dictionaries complete' % len(figs))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
