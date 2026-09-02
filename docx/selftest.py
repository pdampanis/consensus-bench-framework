#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A mutation test: it proves that the checks CAN fail.

A check that always passes is worse than no check, because it gives
false confidence. Here the .docx is deliberately damaged, one way at a
time, and the matching check is required to catch it.

    python3 docx/selftest.py en
"""
import os
import re
import shutil
import subprocess
import sys

import docx
from docx.oxml.ns import qn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config

HERE = config.HERE
BUILD = config.BUILD


def mut_heading(d):
    """V01: changes the title of a section."""
    for p in d.paragraphs:
        if p.style.name == 'Heading 2':
            p.runs[-1].text = 'DAMAGED TITLE'
            return


def mut_caption(d):
    """V02: changes a figure caption."""
    for p in d.paragraphs:
        if p.style.name == 'Image Caption':
            p.runs[-1].text = ' AN ADDITION THAT IS NOT IN THE LATEX'
            return


def mut_border(d):
    """V04: removes the border of one table."""
    t = d.tables[0]
    for b in t._tbl.tblPr.findall(qn('w:tblBorders')):
        t._tbl.tblPr.remove(b)


def mut_cite(d):
    """V05: leaves one reference with no citation at all.

    Deleting ONE occurrence is not enough: the check asks "is it cited
    anywhere?". So the damage it has to catch is that none is left.
    """
    cut = next(i for i, p in enumerate(d.paragraphs)
               if p.text.strip() == config.s('bibliography'))
    body = d.paragraphs[:cut]
    counts = {}
    for p in body:
        for n in re.findall(r'\[(\d+)\]', p.text):
            counts[n] = counts.get(n, 0) + 1
    for p in body:
        for r in p.runs:
            for n, c in counts.items():
                if c == 1 and ('[%s]' % n) in r.text:
                    r.text = r.text.replace('[%s]' % n, '')
                    return
    raise SystemExit('no reference is cited exactly once — change the '
                     'damage this mutant does')


def mut_bib(d):
    """V06: deletes one bibliography entry."""
    for p in d.paragraphs:
        if re.match(r'^\[\d+\]', p.text.strip()):
            p._p.getparent().remove(p._p)
            return


def mut_frontorder(d):
    """V07: moves one preliminary page after the next one."""
    heads = [p for p in d.paragraphs if p.style.name == 'Front Heading NB']
    a, b = heads[1], heads[2]
    b._p.addnext(a._p)


def mut_pagenum(d):
    """V08: makes both Word sections use arabic numerals."""
    for s in d.sections:
        e = s._sectPr.find(qn('w:pgNumType'))
        if e is not None:
            e.set(qn('w:fmt'), 'decimal')


def mut_abbr(d):
    """V12: deletes one abbreviation."""
    for p in d.paragraphs:
        if p.style.name == 'List Entry':
            p._p.getparent().remove(p._p)
            return


def mut_para(d):
    """V15: deletes a whole paragraph."""
    for p in d.paragraphs:
        if p.style.name == 'Body Text' and len(p.text) > 400:
            p._p.getparent().remove(p._p)
            return


def mut_numbering(d):
    """V18: unbinds the automatic numbering from the Heading 2 style."""
    from docx.oxml.ns import qn
    # Looking the style up by NAME fails in some versions of python-docx;
    # the styleId is stable.
    st = next(x.element for x in d.styles
              if x.element.get(qn('w:styleId')) == 'Heading2')
    ppr = st.find(qn('w:pPr'))
    for n in ppr.findall(qn('w:numPr')):
        ppr.remove(n)


def mut_order(d2):
    """V19: moves the first run of a paragraph to the end of it.

    Exactly the defect the converter once had: every word present, the
    sentence backwards.
    """
    from docx.oxml.ns import qn
    for p in d2.paragraphs:
        if p.style.name == 'Body Text' and len(p.runs) > 3:
            r = p.runs[0]._r
            p._p.remove(r)
            p._p.append(r)
            return


MUTANTS = [
    ('V01', mut_heading), ('V02', mut_caption), ('V04', mut_border),
    ('V05', mut_cite), ('V06', mut_bib), ('V07', mut_frontorder),
    ('V08', mut_pagenum), ('V12', mut_abbr), ('V15', mut_para),
    ('V18', mut_numbering), ('V19', mut_order),
]


def run(path):
    env = dict(os.environ, THESIS_DOCX=path)
    p = subprocess.run([sys.executable, os.path.join(HERE, 'verify.py'),
                        config.LANG],
                       capture_output=True, text=True, env=env, cwd=HERE)
    return p.returncode, p.stdout


def main():
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    good = config.out_docx()
    tmp = os.path.join(BUILD, 'mutant-%s.docx' % config.LANG)
    if not os.path.exists(good):
        raise SystemExit('missing %s — run "make docx" first' % good)
    rc, out = run(good)
    if rc != 0:
        print('The UNDAMAGED file does not pass — fix that first.')
        print(out)
        return 1

    bad = []
    for code, fn in MUTANTS:
        shutil.copy(good, tmp)
        d = docx.Document(tmp)
        fn(d)
        d.save(tmp)
        rc, out = run(tmp)
        caught = any(l.split()[:2] == ['FAIL', code]
                     for l in out.split('\n') if l.split())
        print('  %-5s %-42s %s'
              % (code, fn.__doc__.split(':')[1].strip().rstrip('.')[:42],
                 'caught' if caught else '*** NOT CAUGHT'))
        if not caught:
            bad.append(code)
    os.path.exists(tmp) and os.remove(tmp)
    print()
    if bad:
        print('%d checks do not catch their own damage: %s' % (len(bad), bad))
        return 1
    print('[%s] all %d checks catch the damage that belongs to them.'
          % (config.LANG, len(MUTANTS)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
