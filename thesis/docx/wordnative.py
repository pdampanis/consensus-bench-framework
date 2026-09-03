# -*- coding: utf-8 -*-
"""Everything that makes the .docx behave like a REAL Word document.

The problem it solves: if the numbers ("1.2", "Figure 2.3", "[14]") are
typed text, then the moment you add a section by hand every number after
it is wrong and nothing tells you.

So the document uses Word's own machinery instead:

  * HEADING NUMBERING through a multilevel list bound to the styles
    Heading 1/2/3 — add a section and everything renumbers itself. The
    university Guidelines ask for exactly this: "set the chapter title to
    a heading style with numbering".
  * CAPTIONS through SEQ fields that restart in each chapter.
  * CROSS-REFERENCES through REF fields pointing at bookmarks, so "see
    Figure 2.3" still points at the right figure after one is inserted
    before it.

Every field is written WITH ITS CACHED RESULT — the number LaTeX already
computed. The document therefore shows correct numbers BEFORE anyone
presses F9, and the checks can compare them against the PDF.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config

from docx.oxml import OxmlElement
from docx.oxml.ns import qn


# SEQ counter names. ASCII on purpose, and per language: Word field names
# are safer without accents, and a shared name would let the English
# figure counter continue from the Greek one.
def SEQ(kind):
    return config.seq(kind)


NUM_ID = 700          # the multilevel list for headings
ABSTRACT_ID = 700


def el(tag, **attrs):
    e = OxmlElement(tag if ':' in tag else 'w:' + tag)
    for k, v in attrs.items():
        e.set(qn('w:' + k), str(v))
    return e


# ---------------------------------------------------------------------------
# Fields
# ---------------------------------------------------------------------------
def field_runs(instr, cached, rpr=None):
    """The <w:r> elements of one field, carrying a cached result.

    Returns the elements to insert. "dirty" tells Word to recompute the
    field when the document opens; until then `cached` is what shows.
    """
    out = []

    def newr():
        r = el('r')
        if rpr is not None:
            r.append(copy_rpr(rpr))
        return r

    r1 = newr()
    r1.append(el('fldChar', fldCharType='begin', dirty='true'))
    out.append(r1)

    r2 = newr()
    t = OxmlElement('w:instrText')
    t.set(qn('xml:space'), 'preserve')
    t.text = instr
    r2.append(t)
    out.append(r2)

    r3 = newr()
    r3.append(el('fldChar', fldCharType='separate'))
    out.append(r3)

    r4 = newr()
    tt = OxmlElement('w:t')
    tt.set(qn('xml:space'), 'preserve')
    tt.text = cached
    r4.append(tt)
    out.append(r4)

    r5 = newr()
    r5.append(el('fldChar', fldCharType='end'))
    out.append(r5)
    return out


def copy_rpr(rpr):
    import copy
    return copy.deepcopy(rpr)


def text_run(text, rpr=None):
    r = el('r')
    if rpr is not None:
        r.append(copy_rpr(rpr))
    t = OxmlElement('w:t')
    t.set(qn('xml:space'), 'preserve')
    t.text = text
    r.append(t)
    return r


_BK_ID = [1000]


def bookmark(name):
    """(start, end) for a bookmark — they go around the runs."""
    _BK_ID[0] += 1
    i = _BK_ID[0]
    s = el('bookmarkStart', id=str(i), name=name)
    e = el('bookmarkEnd', id=str(i))
    return s, e


def safe_bookmark_name(label):
    """Word accepts letters, digits and "_", up to 40 characters."""
    out = ''.join(c if (c.isalnum() and ord(c) < 128) else '_'
                  for c in label)
    return ('Ref_' + out)[:40]


# ---------------------------------------------------------------------------
# The multilevel list behind the headings
# ---------------------------------------------------------------------------
def heading_numbering_xml():
    """<w:abstractNum> + <w:num> for 1 / 1.1 / 1.1.1.

    LEVEL 0 has an EMPTY lvlText: it counts the chapters without printing
    anything, because the chapter number appears on a line of its own
    ("CHAPTER 1"), as the department template wants. Levels 1 and 2 refer
    to that counter as "%1", which is what produces "1.1" and "1.1.1".
    """
    a = el('abstractNum', abstractNumId=str(ABSTRACT_ID))
    a.append(el('nsid', val='0A0B0C0D'))
    a.append(el('multiLevelType', val='multilevel'))
    a.append(el('tmpl', val='0A0B0C0E'))

    specs = [
        (0, '', 'Heading1', 'nothing', 0, 0),
        (1, '%1.%2', 'Heading2', 'space', 0, 0),
        (2, '%1.%2.%3', 'Heading3', 'space', 0, 0),
    ]
    for ilvl, text, style, suff, left, hang in specs:
        lv = el('lvl', ilvl=str(ilvl))
        lv.append(el('start', val='1'))
        lv.append(el('numFmt', val='decimal'))
        lv.append(el('lvlText', val=text))
        lv.append(el('lvlJc', val='left'))
        lv.append(el('pStyle', val=style))
        lv.append(el('suff', val=suff))
        ppr = el('pPr')
        ind = el('ind', left=str(left), hanging=str(hang))
        ppr.append(ind)
        lv.append(ppr)
        a.append(lv)

    n = el('num', numId=str(NUM_ID))
    n.append(el('abstractNumId', val=str(ABSTRACT_ID)))
    return a, n


def bind_style_to_list(style_el, ilvl):
    """Puts <w:numPr> into the <w:pPr> of a heading style."""
    ppr = style_el.find(qn('w:pPr'))
    if ppr is None:
        ppr = el('pPr')
        style_el.append(ppr)
    for old in ppr.findall(qn('w:numPr')):
        ppr.remove(old)
    npr = el('numPr')
    npr.append(el('ilvl', val=str(ilvl)))
    npr.append(el('numId', val=str(NUM_ID)))
    ppr.insert(0, npr)


# ---------------------------------------------------------------------------
# Turning the markers into fields
# ---------------------------------------------------------------------------
import re as _re

MARKER = _re.compile(r'\[\[(L\|[^\[\]|]*|R\|[^\[\]|]*\|[^\[\]|]*'
                     r'|C\|[FT]\|[^\[\]|]*\|[^\[\]|]*)\]\]')


def _runs_of(p):
    """[(run element, text)] — only the w:r that carry a w:t."""
    out = []
    for r in p.findall(qn('w:r')):
        ts = r.findall(qn('w:t'))
        out.append((r, ''.join(t.text or '' for t in ts)))
    return out


def rebuild_with_markers(p, build):
    """Replaces every marker in the paragraph with what `build` returns.

    IN PLACE, run by run. An earlier version rebuilt the whole paragraph
    and appended the text at the END — which meant that in paragraphs
    containing equations (m:oMath, which is not a w:r) the mathematics
    stayed in front and the text moved behind it. Here only the runs that
    actually carry the marker are touched; everything else, equations
    included, stays exactly where it was.

    build(kind, args, rpr) -> list of elements. Returns how many markers
    were found.
    """
    kids = list(p)
    spans = []          # (offset in the joined text, child index, text)
    whole, pos = [], 0
    for idx, k in enumerate(kids):
        if k.tag != qn('w:r'):
            continue
        t = ''.join(tt.text or '' for tt in k.findall(qn('w:t')))
        if not t:
            continue
        spans.append((pos, idx, t))
        whole.append(t)
        pos += len(t)
    text = ''.join(whole)
    if '[[' not in text:
        return 0

    def locate(off):
        """(child index, local offset) for an offset in the joined text."""
        for start, idx, t in spans:
            if start <= off < start + len(t):
                return idx, off - start
        if spans:
            start, idx, t = spans[-1]
            return idx, len(t)
        return None, 0

    matches = list(MARKER.finditer(text))
    if not matches:
        return 0

    for m in reversed(matches):
        i0, o0 = locate(m.start())
        i1, o1 = locate(m.end() - 1)
        if i0 is None:
            continue
        first, last = kids[i0], kids[i1]
        rpr = first.find(qn('w:rPr'))
        t0 = ''.join(tt.text or '' for tt in first.findall(qn('w:t')))
        t1 = ''.join(tt.text or '' for tt in last.findall(qn('w:t')))
        prefix, suffix = t0[:o0], t1[o1 + 1:]

        parts = m.group(1).split('|')
        new = []
        if prefix:
            new.append(text_run(prefix, rpr))
        new.extend(build(parts[0], parts[1:], rpr))
        if suffix:
            new.append(text_run(suffix, last.find(qn('w:rPr'))))

        for e in new:
            first.addprevious(e)
        for idx in range(i0, i1 + 1):
            k = kids[idx]
            if k.getparent() is p:
                p.remove(k)
        kids = list(p)
        spans, pos = [], 0
        for idx, k in enumerate(kids):
            if k.tag != qn('w:r'):
                continue
            t = ''.join(tt.text or '' for tt in k.findall(qn('w:t')))
            if not t:
                continue
            spans.append((pos, idx, t))
            pos += len(t)
        text = ''.join(t for _s, _i, t in spans)

    return len(matches)


def fix_bookmarks(doc):
    """Gives every bookmark pair a correct, unique id again.

    docxcompose renumbers the ids when it joins the three parts — and it
    gets them wrong: two bookmarks ended up with the same id, so one
    bookmark's end closed the other one and a reference to "Figure 1.1"
    pulled in the WHOLE caption.

    Here the document is read in order and each bookmarkEnd takes the id
    of the nearest open bookmarkStart (a stack). Our bookmarks never
    overlap, so this restores them exactly.
    """
    body = doc.element.body
    stack, nxt, fixed = [], 1, 0
    for e in body.iter():
        if e.tag == qn('w:bookmarkStart'):
            e.set(qn('w:id'), str(nxt))
            stack.append(nxt)
            nxt += 1
        elif e.tag == qn('w:bookmarkEnd'):
            if stack:
                want = str(stack.pop())
                if e.get(qn('w:id')) != want:
                    fixed += 1
                e.set(qn('w:id'), want)
    return fixed, nxt - 1
