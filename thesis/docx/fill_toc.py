#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fills the table of contents and the lists with real page numbers.

Why it is needed: Word's TOC fields are live, but until they carry a
cached result the document opens showing a blank or an instruction. So
the file is rendered to PDF, the real page numbers are read off it, and
they are written into the field as its cached result.

The field STAYS a field: Ctrl+A and F9 make Word recompute it. You just
do not start from an empty page.

It runs twice, because filling the contents changes the pagination: the
second render corrects the numbers of the first.

    python3 docx/fill_toc.py en
"""
import os
import re
import shutil
import subprocess
import sys
import unicodedata

import docx
from docx.oxml.ns import qn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import wordnative as WN

BUILD = config.BUILD

ROMAN = ['', 'i', 'ii', 'iii', 'iv', 'v', 'vi', 'vii', 'viii', 'ix', 'x',
         'xi', 'xii', 'xiii', 'xiv', 'xv', 'xvi', 'xvii', 'xviii', 'xix',
         'xx', 'xxi', 'xxii', 'xxiii', 'xxiv', 'xxv']

# How wide the text column is, in twips (A4 minus 2.54 cm each side)
TEXT_WIDTH = 11906 - 2 * 1440


def norm(s):
    """Normalises PDF text so it can be matched against .docx text.

    Final sigma is a trap: Python's lower() turns "Σ" into "ς" at the end
    of a word and "σ" elsewhere — so the same word compared unequal
    depending on whether the space next to it had been stripped.
    """
    s = unicodedata.normalize('NFKD', s)
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r'[^0-9A-Za-zΑ-Ωα-ω]+', '', s).lower()
    return s.replace('ς', 'σ')


def render(path):
    """Renders to PDF and returns [text of each page]."""
    tmpdir = os.path.join(BUILD, 'toc-%s' % config.LANG)
    os.makedirs(tmpdir, exist_ok=True)
    for f in os.listdir(tmpdir):
        os.remove(os.path.join(tmpdir, f))
    subprocess.run(['soffice', '--headless', '--convert-to', 'pdf',
                    path, '--outdir', tmpdir],
                   check=True, capture_output=True, timeout=600)
    pdf = os.path.join(tmpdir, os.path.basename(path)[:-5] + '.pdf')
    n = int(re.search(r'Pages:\s+(\d+)',
                      subprocess.run(['pdfinfo', pdf], capture_output=True,
                                     text=True).stdout).group(1))
    pages = []
    for i in range(1, n + 1):
        out = subprocess.run(['pdftotext', '-f', str(i), '-l', str(i),
                              pdf, '-'], capture_output=True, text=True)
        pages.append(norm(out.stdout))
    return pages


def printed_number(phys, first_body):
    """The number printed on the page: i, ii, ... before the body,
    1, 2, ... after it."""
    if phys < first_body:
        return ROMAN[phys] if phys < len(ROMAN) else str(phys)
    return str(phys - first_body + 1)


# Pages that BEGIN with their own title — found that way because the
# same text also appears inside the lists.
def own_page_heads():
    return tuple(config.s(k) for k in ('toc', 'list_images', 'list_figures',
                                       'list_tables', 'abbreviations'))


def list_span(pages, first_body):
    """The pages from the table of contents to the start of the body.

    From the second round on the lists are full: every title and every
    caption appears in them TOO. Without this exclusion the search finds
    the page of the list instead of the real one.
    """
    lo = next((i for i, t in enumerate(pages)
               if t.startswith(norm(config.s('toc')))), None)
    if lo is None:
        return (10 ** 9, 10 ** 9)
    return (lo, max(lo + 1, first_body - 1))


def find_page(pages, text, start=0, span=(10 ** 9, 10 ** 9)):
    """The first page OUTSIDE the lists that contains this text."""
    key = norm(text)[:60]
    if not key:
        return None
    lo, hi = span
    for i in range(start, len(pages)):
        if lo <= i < hi:
            continue
        if key in pages[i]:
            return i + 1
    return None


def find_own_page(pages, text):
    """The page that BEGINS with this title."""
    key = norm(text)
    for i, t in enumerate(pages):
        if t.startswith(key):
            return i + 1
    return None


def toc_entries(d, pages, first_body):
    """[(level, text, page)] for the three fields."""
    out = {'toc': [], 'fig': [], 'tab': []}
    ch = sec = subn = 0
    cursor = 0
    span = list_span(pages, first_body)
    for p in d.paragraphs:
        st = p.style.name
        txt = ' '.join(p.text.split())
        if not txt:
            continue
        if st == 'Heading 1':
            ch += 1; sec = subn = 0
            label, lvl, bucket = txt, 1, 'toc'
        elif st == 'Heading 2':
            sec += 1; subn = 0
            label, lvl, bucket = '%d.%d %s' % (ch, sec, txt), 2, 'toc'
        elif st == 'Heading 3':
            subn += 1
            label = '%d.%d.%d %s' % (ch, sec, subn, txt)
            lvl, bucket = 3, 'toc'
        elif st == 'Front Heading NB':
            label, lvl, bucket = txt, 1, 'toc'
        elif st == 'Image Caption':
            label, lvl, bucket = txt, 1, 'fig'
        elif st == 'Table Caption':
            label, lvl, bucket = txt, 1, 'tab'
        else:
            continue
        # The lists' own pages are found by what they START with;
        # everything else is looked for outside the span of the lists.
        if txt in own_page_heads():
            pg = find_own_page(pages, txt)
        else:
            pg = find_page(pages, txt, cursor, span)
            if pg:
                cursor = max(0, pg - 1)
        out[bucket].append((lvl, label,
                            printed_number(pg, first_body) if pg else '?'))
    return out


def first_body_page(pages):
    """The physical page where arabic numbering starts (CHAPTER 1)."""
    for i, t in enumerate(pages):
        if norm(config.s('chapter')) + '1' in t:
            return i + 1
    return 1


def field_paragraph(body, instr_key):
    """The paragraph holding the field, and the one holding its end."""
    kids = list(body)
    for i, k in enumerate(kids):
        if k.tag != qn('w:p'):
            continue
        instr = ''.join(t.text or '' for t in k.iter(qn('w:instrText')))
        if instr_key not in instr:
            continue
        for j in range(i, len(kids)):
            if kids[j].tag != qn('w:p'):
                continue
            for r in kids[j].iter(qn('w:fldChar')):
                if r.get(qn('w:fldCharType')) == 'end':
                    return kids, i, j
        return kids, i, i
    return None, None, None


def clear_result(body, kids, i, j):
    """Deletes the field's PREVIOUS cached result.

    Without this the second round inserted the whole contents a SECOND
    time.
    """
    k = kids[i]
    for x in kids[i + 1:j + 1]:
        if x.tag == qn('w:p'):
            body.remove(x)
    seen = False
    endrun = None
    for r in list(k.findall(qn('w:r'))):
        fc = r.find(qn('w:fldChar'))
        typ = fc.get(qn('w:fldCharType')) if fc is not None else None
        if typ == 'separate':
            seen = True
            continue
        if typ == 'end':
            endrun = r
            continue
        if seen:
            k.remove(r)
    if endrun is None:
        endrun = WN.el('r')
        endrun.append(WN.el('fldChar', fldCharType='end'))
        k.append(endrun)
    else:
        k.remove(endrun)
        k.append(endrun)
    return k, endrun


def fill(doc, instr_key, entries):
    """Writes the entries as the field's cached result."""
    body = doc.element.body
    kids, i, j = field_paragraph(body, instr_key)
    if kids is None:
        return 0
    k, endrun = clear_result(body, kids, i, j)

    anchor = k
    for lvl, label, page in entries:
        p = WN.el('p')
        ppr = WN.el('pPr')
        ppr.append(WN.el('pStyle', val='TOC%d' % lvl))
        tabs = WN.el('tabs')
        tabs.append(WN.el('tab', val='right', leader='dot',
                          pos=str(TEXT_WIDTH)))
        ppr.append(tabs)
        p.append(ppr)
        p.append(WN.text_run(label))
        p.append(WN.text_run('\t'))
        p.append(WN.text_run(page))
        anchor.addnext(p)
        anchor = p
    if anchor is not k:
        k.remove(endrun)
        anchor.append(endrun)
    return len(entries)


def main():
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    docx_path = config.out_docx()
    if shutil.which('soffice') is None:
        print('soffice not found — the contents stay empty; in Word press '
              'Ctrl+A and then F9', file=sys.stderr)
        return 0
    for round_ in (1, 2):
        pages = render(docx_path)
        fb = first_body_page(pages)
        d = docx.Document(docx_path)
        ent = toc_entries(d, pages, fb)
        n1 = fill(d, 'TOC \\o', ent['toc'])
        n2 = fill(d, 'Image Caption,1', ent['fig'])
        n3 = fill(d, 'Table Caption,1', ent['tab'])
        d.save(docx_path)
        unknown = sum(1 for b in ent.values() for e in b if e[2] == '?')
        print('  [%s] round %d: contents %d, figures %d, tables %d%s'
              % (config.LANG, round_, n1, n2, n3,
                 '   (page not found: %d)' % unknown if unknown else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
