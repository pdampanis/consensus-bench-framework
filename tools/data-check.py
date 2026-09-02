#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Reports which of your own measurements the thesis is still waiting for.

Three things it checks, in order of how badly each one would hurt:

  D01  A \\meas{key} used in the text that is not declared at all.
       This is a build error: LaTeX would print nothing and the sentence
       would silently lose its number.

  D02  A measurement declared but never used. Either the text that
       needed it was cut, or it was never written. Worth knowing before
       you run an experiment nobody is going to cite.

  D03  Measurements still pending. Not an error — it is the normal state
       until the campaign runs. Printed as a checklist, grouped by the
       research question they answer, so you can see what one more
       afternoon of benchmarking would let you write.

    python3 tools/data-check.py            report
    python3 tools/data-check.py --strict   also fail while anything is pending
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REG = os.path.join(ROOT, 'data', 'measurements.tex')
CONTENT = [os.path.join(ROOT, 'content', lang)
           for lang in ('en', 'el')]

DECL = re.compile(r'\\(declaremeasurement|pendingmeasurement)\{([^}]+)\}'
                  r'(?:\{([^}]*)\})?')
USE = re.compile(r'\\meas\{([^}]+)\}')
SECTION = re.compile(r'^%\s{2}(RQ\d[^\n]*|Predictions[^\n]*)')


def strip_comment(line):
    """Drop the LaTeX comment from one line, honouring \\%."""
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
    return ''.join(res)


def registry():
    """{key: (status, value, section)} in file order.

    Read line by line, because the section titles live IN the comments
    while the declarations must be read with comments stripped — this
    file documents its own syntax in its header, and the example
    \\pendingmeasurement in that header is not a declaration.
    """
    out, section = {}, 'unfiled'
    for line in open(REG, encoding='utf-8'):
        head = SECTION.match(line)
        if head:
            section = head.group(1).strip()
            continue
        code = strip_comment(line)
        for m in DECL.finditer(code):
            kind, key, val = m.group(1), m.group(2), m.group(3)
            out[key] = ('declared' if kind == 'declaremeasurement'
                        else 'pending', val, section)
    return out


def uses():
    """{key: [where it is used]}"""
    found = {}
    for d in CONTENT:
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith('.tex'):
                continue
            path = os.path.join(d, name)
            for i, line in enumerate(open(path, encoding='utf-8'), 1):
                for m in USE.finditer(line):
                    found.setdefault(m.group(1), []).append(
                        '%s/%s:%d' % (os.path.basename(d), name, i))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--strict', action='store_true',
                    help='fail while any measurement is still pending')
    a = ap.parse_args()

    reg, used = registry(), uses()
    fail = []

    undeclared = sorted(k for k in used if k not in reg)
    for k in undeclared:
        fail.append('D01 %s — used in %s but not declared in '
                    'data/measurements.tex' % (k, ', '.join(used[k][:3])))

    unused = sorted(k for k in reg if k not in used)
    pending = [k for k, v in reg.items() if v[0] == 'pending']
    declared = [k for k, v in reg.items() if v[0] == 'declared']

    print('measurements declared: %d   filled in: %d   still pending: %d'
          % (len(reg), len(declared), len(pending)))

    for f in fail:
        print('  ERROR ' + f)

    if unused:
        print('\nD02 declared but not used anywhere in the text (%d):'
              % len(unused))
        for k in unused:
            print('  %-34s %s' % (k, reg[k][2]))
        print('  (normal while the chapters that will use them are '
              'unwritten — check again before submission)')

    if pending:
        print('\nD03 waiting on your benchmark campaign (%d):' % len(pending))
        by_sec = {}
        for k in pending:
            by_sec.setdefault(reg[k][2], []).append(k)
        for sec in sorted(by_sec):
            print('\n  %s' % sec)
            for k in sorted(by_sec[sec]):
                where = ', '.join(used.get(k, [])[:2]) or '— not cited yet'
                print('    [ ] %-32s %s' % (k, where))

    if fail:
        return 1
    if a.strict and pending:
        print('\n--strict: %d measurement(s) still pending' % len(pending))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
