#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Checks that the Greek edition still says the same things as the English.

Not the same words — the same *commitments*. A translation can be good
prose and still quietly drop a citation, lose a "preprint" marker, or
renumber a figure. Those are the failures an examiner finds and the
author never sees, because nobody reads two editions side by side.

So this compares, per chapter, the things that must be identical in any
faithful translation:

  P01  block ids, and their order
  P02  the multiset of \\cite keys
  P03  the count of \\grey, \\prep and \\unver source-quality markers
  P04  the set of \\label keys and of \\ref keys
  P05  the number of sections and subsections
  P06  the number of figures and tables
  P07  the number of \\meas measurement slots

Anything that differs is printed with the block it is in, so it can be
fixed rather than merely noticed.

    python3 tools/parity.py
"""
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, 'content', 'en')
EL = os.path.join(ROOT, 'content', 'el')

BLK = re.compile(r'^%%blk\s+([A-Za-z0-9_.:-]+)', re.M)


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


def facts(path):
    raw = open(path, encoding='utf-8').read()
    src = strip_comments(raw)
    cites = Counter()
    for m in re.finditer(r'\\cite(?:\[[^\]]*\])?\{([^}]*)\}', src):
        for k in m.group(1).split(','):
            if k.strip():
                cites[k.strip()] += 1
    return {
        'blocks': BLK.findall(raw),
        'cites': cites,
        'grey': len(re.findall(r'\\grey\b', src)),
        'prep': len(re.findall(r'\\prep\b', src)),
        'unver': len(re.findall(r'\\unver\b', src)),
        'labels': sorted(set(re.findall(r'\\label\{([^}]*)\}', src))),
        'refs': Counter(re.findall(r'\\ref\{([^}]*)\}', src)),
        'sections': len(re.findall(r'\\section\{', src)),
        'subsections': len(re.findall(r'\\subsection\{', src)),
        'figures': len(re.findall(r'\\dgfigure\{', src))
                   + len(re.findall(r'\\begin\{figure\}', src)),
        'tables': len(re.findall(r'\\begin\{table\}', src)),
        'meas': Counter(re.findall(r'\\meas\{([^}]*)\}', src)),
    }


def main():
    if not os.path.isdir(EN):
        print('no content/en yet')
        return 0
    names = sorted(f for f in os.listdir(EN) if f.endswith('.tex'))
    bad = 0
    for name in names:
        elp = os.path.join(EL, name)
        print('=== %s ===' % name)
        if not os.path.exists(elp):
            print('  P00 no Greek edition of this chapter at all')
            bad += 1
            continue
        a, b = facts(os.path.join(EN, name)), facts(elp)

        if a['blocks'] != b['blocks']:
            bad += 1
            only_en = [x for x in a['blocks'] if x not in set(b['blocks'])]
            only_el = [x for x in b['blocks'] if x not in set(a['blocks'])]
            if only_en or only_el:
                print('  P01 block ids differ — only EN: %s | only EL: %s'
                      % (only_en[:6], only_el[:6]))
            else:
                first = next(i for i, (x, y) in
                             enumerate(zip(a['blocks'], b['blocks'])) if x != y)
                print('  P01 same ids, different order — first at position %d: '
                      'EN %s / EL %s'
                      % (first, a['blocks'][first], b['blocks'][first]))
        else:
            print('  ok  P01 %d blocks, same ids, same order'
                  % len(a['blocks']))

        if a['cites'] != b['cites']:
            bad += 1
            diff = sorted(set(a['cites']) | set(b['cites']))
            rows = [(k, a['cites'].get(k, 0), b['cites'].get(k, 0))
                    for k in diff if a['cites'].get(k) != b['cites'].get(k)]
            print('  P02 citations differ (key, EN, EL): %s' % rows[:8])
        else:
            print('  ok  P02 %d citations, %d distinct keys, identical'
                  % (sum(a['cites'].values()), len(a['cites'])))

        for k, label in (('grey', 'grey-literature'), ('prep', 'preprint'),
                         ('unver', 'unverified-data')):
            if a[k] != b[k]:
                bad += 1
                print('  P03 %s markers: EN %d, EL %d' % (label, a[k], b[k]))
        if all(a[k] == b[k] for k in ('grey', 'prep', 'unver')):
            print('  ok  P03 source markers identical (grey %d, preprint %d, '
                  'unverified %d)' % (a['grey'], a['prep'], a['unver']))

        if a['labels'] != b['labels']:
            bad += 1
            print('  P04 labels only EN: %s | only EL: %s'
                  % ([x for x in a['labels'] if x not in b['labels']],
                     [x for x in b['labels'] if x not in a['labels']]))
        elif a['refs'] != b['refs']:
            bad += 1
            diff = [(k, a['refs'].get(k, 0), b['refs'].get(k, 0))
                    for k in set(a['refs']) | set(b['refs'])
                    if a['refs'].get(k) != b['refs'].get(k)]
            print('  P04 cross-references differ (key, EN, EL): %s' % diff[:8])
        else:
            print('  ok  P04 %d labels, %d cross-references, identical'
                  % (len(a['labels']), sum(a['refs'].values())))

        for k in ('sections', 'subsections', 'figures', 'tables', 'meas'):
            av, bv = a[k], b[k]
            if isinstance(av, Counter):
                if av != bv:
                    bad += 1
                    print('  P07 %s differ: EN %s, EL %s' % (k, dict(av), dict(bv)))
            elif av != bv:
                bad += 1
                print('  P0%d %s: EN %d, EL %d'
                      % (5 if k in ('sections', 'subsections') else 6,
                         k, av, bv))
        if (a['sections'], a['subsections'], a['figures'], a['tables']) == \
           (b['sections'], b['subsections'], b['figures'], b['tables']):
            print('  ok  P05/P06 %d sections, %d subsections, %d figures, '
                  '%d tables' % (a['sections'], a['subsections'],
                                 a['figures'], a['tables']))
    print('\n%s' % ('PARITY OK' if not bad
                    else '%d parity problem(s)' % bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
