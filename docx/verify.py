#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Checks that thesis-<lang>.docx says THE SAME as thesis-<lang>.pdf.

This is not decoration. Sixteen checks compare the .docx against the
SOURCE — the .aux files, the .bbl, the chapter .tex — and the script
exits 1 if any of them finds a difference. The two renderings of the
thesis are not allowed to say different things.

  V01 headings   V02 figures    V03 tables     V04 table borders
  V05 citations  V06 bibliography              V07 page order
  V08 page numbering            V09 Word fields
  V10 equations  V11 fonts      V12 abbreviations
  V15 nothing lost              V18 nothing typed
  V19 nothing reordered         V20 the numbers shown before F9

docx/selftest.py damages the .docx once per check and requires the
matching check to catch it. All sixteen do.

TWO THINGS HERE ARE REPORTS, NOT CHECKS, and they say so: V14 (length
against the PDF) and V16 (short captions) have no failure path, because
neither has a threshold that means anything on its own. They print, and
the author judges. So do the length, abstract and keyword lines.

    python3 docx/verify.py en
"""
import json
import os
import re
import subprocess
import sys
import zipfile

import docx
from docx.oxml.ns import qn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
config.select(sys.argv[1] if len(sys.argv) > 1
              and sys.argv[1] in ('en', 'el') else 'en')

import texparse as T
import frontmatter as F
import wordnative as WN
import abbreviations as A
from assemble import chapter_prose

BUILD = config.BUILD
ROOT = T.ROOT
# selftest.py points this at a DELIBERATELY DAMAGED copy, to prove that
# each check catches its own damage.
DOCX = os.environ.get('THESIS_DOCX') or config.out_docx()

# A "long word" for the loss and order checks. Long words are used
# because they are rare enough to be near-unique and common enough to
# cover every paragraph.
LONGWORD = {'en': r'[A-Za-z]{9,}',
            'el': r'[Α-Ωα-ωΆ-Ώά-ώΪΫϊϋΐΰ]{9,}'}[config.LANG]

FAIL, WARN, OK, INFO = [], [], [], []
_BBL, _ = T.read_bbl_order()
_LAB = T.read_labels()


def fail(code, msg):
    FAIL.append('%s %s' % (code, msg))


def warn(code, msg):
    WARN.append('%s %s' % (code, msg))


def ok(code, msg):
    OK.append('%s %s' % (code, msg))


def norm(s):
    """Normalisation for comparison: spaces, quotes, dashes."""
    s = s.replace('’', "'").replace('‘', "'")
    s = s.replace('“', '"').replace('”', '"')
    s = s.replace('«', '"').replace('»', '"')
    s = s.replace('–', '--').replace('—', '---')
    s = re.sub(r'\s+', ' ', s)
    return s.strip()


def _citenum(m):
    nums = [_BBL[k] for k in T.cite_keys(m.group(1)) if k in _BBL]
    return _compress(nums) if nums else ''


def _compress(nums):
    """The same convention as tex2pandoc.py: [2]-[4], [7]."""
    nums = sorted(set(nums))
    out, i = [], 0
    while i < len(nums):
        j = i
        while j + 1 < len(nums) and nums[j + 1] == nums[j] + 1:
            j += 1
        if j - i >= 2:
            out.append('[%d]-[%d]' % (nums[i], nums[j]))
        else:
            out.extend('[%d]' % n for n in nums[i:j + 1])
        i = j + 1
    return ', '.join(out)



def eat_argument_commands(s):
    r"""The last stage before the catch-all \command strip.

    The catch-all removes the command NAME and leaves its braces, whose
    contents then read as text. Three kinds have to be handled first:
    what survives into the .docx as a character, quotation marks, and
    commands whose argument must disappear WITH them. Without the last
    one, \label{sec:background} left the word "background" in the
    comparison and every check that counts words reported a loss that
    was not there.
    """
    s = s.replace('\\S', '§').replace('\\%', '%')
    s = s.replace("``", '"').replace("''", '"')
    s = re.sub(r'\\enquote\s*\{([^{}]*)\}', r'"\1"', s)
    # environments leave no text of their own
    s = re.sub(r'\\(begin|end)\s*\{[^{}]*\}(\[[^\]]*\])?', ' ', s)
    # commands whose argument is machinery, not prose
    s = re.sub(r'\\(label|index|vspace|hspace|setstretch|includegraphics|'
               r'addcontentsline|markboth)\*?\s*(\[[^\]]*\])?'
               r'\{[^{}]*\}(\{[^{}]*\})*', ' ', s)
    return s


def detex(s):
    """Strips LaTeX formatting AND the mathematics.

    The mathematics goes from both sides: in the .docx it becomes OMML
    (real Word equations, as the Guidelines require) and does not appear
    in python-docx's .text. This comparison is about the TEXT; that the
    equations exist is checked separately, in V10.
    """
    s = re.sub(r'\$[^$]*\$', '', s)
    # \protect exists only so a fragile command survives being moved into
    # a caption or a heading. Left in place, the unwrapping below glued it
    # to the word it protected ("\protect\en{quorum}" -> "\protectquorum")
    # and the catch-all then ate the word along with the command.
    s = s.replace('\\protect', '')
    # \cite{key} -> [n]: in the .docx the citations are already numbers
    s = re.sub(r'\\cite(?:\[[^\]]*\])?\{([^}]*)\}', _citenum, s)
    # \ref{sec:x} -> the number LaTeX knows (from the .aux files)
    s = re.sub(r'\\ref\{([^}]*)\}',
               lambda m: _LAB.get(m.group(1), ('?',))[0], s)
    # LaTeX spacing: \, \; \! and "\ " (backslash + space)
    s = re.sub(r'\\[,;!:> ]', ' ', s)
    # characters that need a backslash in LaTeX: \% \& \_ \# \$
    s = re.sub(r'\\([%&_#$])', r'\1', s)
    for _ in range(6):
        s2 = re.sub(r'\\(textbf|textit|emph|texttt|en|enup|textsc|'
                    r'text|mathrm|si|num)\{([^{}]*)\}', r'\2', s)
        if s2 == s:
            break
        s = s2
    # \grey / \prep become superscript G / P — they are part of the text
    # A space each side: in the .docx the superscript is its own run, so
    # the text stream reads "documentation G", and without the space the
    # LaTeX side produced one 14-letter word where the .docx has one of
    # 13 — a difference the order check then reported as real.
    s = re.sub(r'\\grey\b', ' G ', s)
    s = re.sub(r'\\prep\b', ' P ', s)
    s = re.sub(r'\\unver\b', ' U ', s)
    # Characters that DO survive into the .docx and would otherwise be
    # eaten by the catch-all below.
    s = eat_argument_commands(s)
    s = re.sub(r'\\[a-zA-Z]+\s*', '', s)
    s = s.replace('{', '').replace('}', '')
    s = s.replace('~', ' ')
    return norm(s)


def detex_keepmath(s, mark):
    """Like detex, but keeps equation markers already substituted in."""
    s = s.replace('\\protect', '')
    s = re.sub(r'\\cite(?:\[[^\]]*\])?\{([^}]*)\}', _citenum, s)
    s = re.sub(r'\\ref\{([^}]*)\}',
               lambda m: _LAB.get(m.group(1), ('?',))[0], s)
    s = re.sub(r'\\caption\s*\[[^\]]*\]', r'\\caption', s)
    s = re.sub(r'\\[,;!:> ]', ' ', s)
    s = re.sub(r'\\([%&_#$])', r'\1', s)
    for _ in range(6):
        s2 = re.sub(r'\\(textbf|textit|emph|texttt|en|enup|textsc|'
                    r'text|mathrm|si|num)\{([^{}]*)\}', r'\2', s)
        if s2 == s:
            break
        s = s2
    # A space each side: in the .docx the superscript is its own run, so
    # the text stream reads "documentation G", and without the space the
    # LaTeX side produced one 14-letter word where the .docx has one of
    # 13 — a difference the order check then reported as real.
    # The source markers are not words. V19 compares an ORDER, and a
    # single letter carries no order information — it only decides
    # whether the word before it is 13 letters or 14.
    s = re.sub(r'\\(grey|prep|unver)\b', ' ', s)
    s = eat_argument_commands(s)
    s = re.sub(r'\\[a-zA-Z]+\s*', '', s)
    s = s.replace('{', '').replace('}', '').replace('~', ' ')
    return s


def chapter_docx_text(paras, d):
    """The .docx text from "CHAPTER 1" to the bibliography."""
    a = next((i for i, p in enumerate(paras)
              if p.style.name == 'Chapter Label'), 0)
    b = next((i for i, p in enumerate(paras)
              if norm(p.text) == config.s('bibliography')), len(paras))
    cells = '\n'.join(c.text for t in d.tables
                      for row in t.rows for c in row.cells)
    return '\n'.join(plain(p) for p in paras[a:b]) + '\n' + cells


def plain(p):
    """A paragraph's text WITHOUT the superscript source markers.

    In the .docx the G of \\grey sits in its own run, directly against
    the word before it, so p.text reads "documentationG". Dropping the
    superscripts makes both sides say "documentation" and stops a word
    count from reporting a loss that is not there.
    """
    out = []
    for r in p.runs:
        if r.font.superscript:
            continue
        out.append(r.text)
    return ''.join(out)


def frontmatter_order():
    """The order of the front-matter headings, as main-<lang>.tex has it.

    It is READ, not written here, so that reordering the pages in one
    edition and not the other shows up as a failure.
    """
    main = T.strip_comments(
        open(config.main_tex(), encoding='utf-8').read())
    body = main[main.index('\\begin{document}'):]
    out = []
    for m in re.finditer(r'\\(?:include|input)\{frontmatter/'
                         r'(en|el)/([A-Za-z-]+)\}'
                         r'|\\(tableofcontents|listoffigures|listoftables)',
                         body):
        if m.group(2):
            f = os.path.join(ROOT, 'frontmatter', m.group(1),
                             m.group(2) + '.tex')
            if not os.path.exists(f):
                continue
            src = T.strip_comments(open(f, encoding='utf-8').read())
            for h in re.finditer(r'\\frontheading\s*\{([^{}]*)\}', src):
                out.append(norm(h.group(1)))
        else:
            keep = config.LANG
            out.append({'tableofcontents': config.s('toc'),
                        'listoffigures': config.s('list_figures'),
                        'listoftables': config.s('list_tables')}[m.group(3)])
            config.select(keep)
    return out


def joined_captions(paras, style):
    """A caption plus the "Caption Cont" after it = the long caption.

    The .docx keeps them apart so that the list picks up the short one;
    the comparison against LaTeX is done on the JOINED form.
    """
    out = []
    for i, p in enumerate(paras):
        if p.style.name != style:
            continue
        txt = p.text
        for q in paras[i + 1:]:
            if q.style.name == 'Caption Cont':
                txt += ' ' + q.text
                break        # one continuation per caption, and it ends there
            # A table's source note sits BETWEEN the caption and its
            # continuation, so it is skipped without being counted.
            if q.style.name != 'Table Note' and q.text.strip():
                break
        out.append(norm(txt))
    return out


def firstdiff(a, b):
    """Exactly where they diverge — otherwise truncating at 90 characters
    hides the difference and the check becomes useless."""
    x = re.sub(r'\s+', '', a)
    y = re.sub(r'\s+', '', b)
    i = next((i for i in range(min(len(x), len(y))) if x[i] != y[i]),
             min(len(x), len(y)))
    lo = max(0, i - 40)
    return ('offset %d\n      DOCX  ...%s...\n      LaTeX ...%s...'
            % (i, x[lo:i + 40], y[lo:i + 40]))


def same_text(a, b):
    """Compare ignoring the spaces the mathematics leaves behind."""
    return re.sub(r'\s+', '', a) == re.sub(r'\s+', '', b)


def main():
    d = docx.Document(DOCX)
    man = json.load(open(config.manifest(), encoding='utf-8'))
    labels = T.read_labels()
    bblnum, _bbltext = T.read_bbl_order()

    paras = d.paragraphs
    # MIND THIS: d.paragraphs does NOT include the table cells. Without
    # them every word count comes out smaller than the real one.
    cell_text = '\n'.join(c.text for t in d.tables
                          for row in t.rows for c in row.cells)
    text = '\n'.join(p.text for p in paras) + '\n' + cell_text

    # The text BEFORE the bibliography. Needed separately: the entries
    # themselves begin "[1]"..."[37]", so if they are counted the citation
    # check always finds every number and can never fail.
    cut = next((i for i, p in enumerate(paras)
                if norm(p.text) == config.s('bibliography')), len(paras))
    body_text = '\n'.join(p.text for p in paras[:cut]) + '\n' + cell_text

    # -- V01 headings: same order, same titles, same numbering ----------
    # The numbers are no longer written into the text — Word's multilevel
    # list produces them. So what is checked here is the POSITION, which
    # determines them uniquely.
    got, doc_by_num, chapters = [], {}, []
    ch = sec = subn = 0
    for p in paras:
        st = p.style.name
        if st not in ('Heading 1', 'Heading 2', 'Heading 3'):
            continue
        title = norm(p.text)
        if title == config.s('bibliography'):
            continue
        got.append((st, title))
        if st == 'Heading 1':
            ch += 1; sec = subn = 0
            chapters.append(title)
            doc_by_num[str(ch)] = title
        elif st == 'Heading 2':
            sec += 1; subn = 0
            doc_by_num['%d.%d' % (ch, sec)] = title
        else:
            subn += 1
            doc_by_num['%d.%d.%d' % (ch, sec, subn)] = title

    src_all = ''.join(T.strip_comments(open(p, encoding='utf-8').read())
                      for p in config.chapters())
    titled = {}
    for m in re.finditer(r'\\(chapter|section|subsection)\*?\s*'
                         r'(?:\[[^\]]*\])?\s*\{', src_all):
        title, i = T.match_brace(src_all, m.end() - 1)
        m2 = re.match(r'\s*\\label\{([^}]*)\}', src_all[i:])
        if m2:
            titled[m2.group(1)] = detex(title)

    aux_bad = []
    for key, title in sorted(titled.items()):
        if key not in labels:
            aux_bad.append('%s: not present in the .aux files' % key)
            continue
        num = labels[key][0]
        got_title = doc_by_num.get(num)
        if got_title is None:
            aux_bad.append('%s: LaTeX numbers it %s, the .docx has no '
                           'heading at that position' % (key, num))
        elif not same_text(norm(got_title), title):
            aux_bad.append('%s (%s): .aux "%s" / DOCX "%s"'
                           % (key, num, title[:45], got_title[:45]))
    if aux_bad:
        for b in aux_bad:
            fail('V01', 'numbering against the .aux — ' + b)
    else:
        ok('V01', '%d section labels: the .aux number matches the position '
           'of the SAME title inside the .docx' % len(titled))

    lvl = {'chapter': 'Heading 1', 'section': 'Heading 2',
           'subsection': 'Heading 3'}
    want = [(lvl[h['level']], detex(h['title']))
            for h in man['headings'] if h['level'] in lvl]
    if len(got) != len(want):
        fail('V01', 'headings: DOCX %d, LaTeX %d' % (len(got), len(want)))
    else:
        bad = [(a, b) for a, b in zip(got, want) if a[0] != b[0]]
        txt = [(a[1], b[1]) for a, b in zip(got, want)
               if not same_text(a[1], b[1])]
        for a, b in bad:
            fail('V01', 'level: DOCX %s / LaTeX %s' % (a, b))
        for a, b in txt:
            fail('V01', 'title:\n      DOCX "%s"\n      LaTeX "%s"' % (a, b))
        if not bad and not txt:
            ok('V01', '%d headings agree in order and in title' % len(want))

    # -- V02 figures -------------------------------------------------------
    caps = joined_captions(paras, 'Image Caption')
    want_caps = [detex('%s %s: %s' % (config.s('figure'), f['number'],
                                      f['caption']))
                 for f in man['figures']]
    if len(caps) != len(want_caps):
        fail('V02', 'figures: DOCX %d, LaTeX %d' % (len(caps), len(want_caps)))
    else:
        diff = [(a, b) for a, b in zip(caps, want_caps)
                if not same_text(a, b)]
        if diff:
            for a, b in diff:
                fail('V02', 'caption, %s' % firstdiff(a, b))
        else:
            ok('V02', '%d figures, captions identical' % len(caps))
    # One image per "Captioned Figure" — not a plain total, because the
    # cover also carries the institution's emblem.
    figpars = [p for p in paras if p.style.name == 'Captioned Figure']
    per = [p._p.xml.count('<pic:pic') for p in figpars]
    logo = sum(p._p.xml.count('<pic:pic') for p in paras
               if p.style.name == 'Front Center')
    if len(figpars) != len(man['figures']) or any(n != 1 for n in per):
        fail('V02', 'images per figure: %s (expected %d figures with one '
             'image each)' % (per, len(man['figures'])))
    elif logo != 1:
        fail('V02', "the institution's emblem on the cover: %d images "
             '(expected 1)' % logo)
    else:
        ok('V02', 'each figure holds EXACTLY one image (%d) + the emblem on '
           'the cover' % len(figpars))

    # -- V03 tables --------------------------------------------------------
    tcaps = joined_captions(paras, 'Table Caption')
    want_t = [detex('%s %s: %s' % (config.s('table'), t['number'],
                                   t['caption']))
              for t in man['tables']]
    if not all(same_text(a, b) for a, b in zip(tcaps, want_t)) \
            or len(tcaps) != len(want_t):
        fail('V03', 'table captions differ: DOCX %d, LaTeX %d'
             % (len(tcaps), len(want_t)))
        for a, b in zip(tcaps, want_t):
            if not same_text(a, b):
                fail('V03', 'caption, %s' % firstdiff(a, b))
    else:
        ok('V03', '%d tables, captions identical' % len(tcaps))
    # Where the caption sits: it must precede the table in the body XML.
    kids = list(d.element.body)
    tags = [k.tag.split('}')[1] for k in kids]
    above = 0
    for i, k in enumerate(kids):
        if tags[i] != 'tbl':
            continue
        j = i - 1
        while j >= 0 and tags[j] == 'p' and not ''.join(
                kids[j].itertext()).strip():
            j -= 1
        st = kids[j].find(qn('w:pPr'))
        st = st.find(qn('w:pStyle')) if st is not None else None
        if st is not None and st.get(qn('w:val')) == 'TableCaption':
            above += 1
    if above != len(d.tables):
        fail('V03', 'caption ABOVE the table: %d of %d (Guidelines: "it is '
             'always above the table")' % (above, len(d.tables)))
    else:
        ok('V03', 'in all %d tables the caption precedes the table' % above)
    if len(d.tables) != len(man['tables']):
        fail('V03', 'tables in the DOCX: %d, in the LaTeX: %d'
             % (len(d.tables), len(man['tables'])))

    # -- V04 table borders (a Guidelines requirement) ---------------------
    noframe = []
    for i, t in enumerate(d.tables, 1):
        b = t._tbl.tblPr.find(qn('w:tblBorders'))
        edges = ([] if b is None else
                 [e for e in ('top', 'left', 'bottom', 'right')
                  if b.find(qn('w:' + e)) is not None
                  and b.find(qn('w:' + e)).get(qn('w:val')) == 'single'])
        if len(edges) != 4:
            noframe.append(i)
    if noframe:
        fail('V04', 'tables with no outer border: %s' % noframe)
    else:
        ok('V04', 'all %d tables have an outer border' % len(d.tables))

    # -- V05 citations -----------------------------------------------------
    nums_docx = set(int(n) for n in re.findall(r'\[(\d+)\]', body_text))
    nums_tex = set(bblnum.values())
    only_tex = sorted(nums_tex - nums_docx)
    only_docx = sorted(nums_docx - nums_tex)
    if only_tex:
        fail('V05', 'references never cited in the DOCX: %s' % only_tex)
    if only_docx:
        warn('V05', 'bracketed numbers with no matching reference: %s'
             % only_docx)
    if not only_tex and not only_docx:
        ok('V05', 'all %d references are cited, and none extra'
           % len(nums_tex))

    # -- V06 bibliography --------------------------------------------------
    bib = [p for p in paras
           if re.match(r'^\[\d+\]', p.text.strip())]
    if len(bib) != len(bblnum):
        fail('V06', 'bibliography entries: DOCX %d, .bbl %d'
             % (len(bib), len(bblnum)))
    else:
        ok('V06', '%d bibliography entries, as many as in the .bbl'
           % len(bib))

    # -- V07 the order of the preliminary pages ---------------------------
    order = frontmatter_order()
    heads = [norm(p.text) for p in paras
             if p.style.name == 'Front Heading NB']
    pos, missing = -1, []
    for name in order:
        found = next((i for i, h in enumerate(heads)
                      if h.startswith(name) and i > pos), None)
        if found is None:
            missing.append(name)
        else:
            pos = found
    if missing:
        fail('V07', 'missing or out of order: %s' % ', '.join(missing))
    else:
        ok('V07', 'the %d preliminary pages of the .docx are in the order '
           'main-%s.tex defines: %s'
           % (len(order), config.LANG, ' -> '.join(order)))

    # -- V08 page numbering ------------------------------------------------
    fmts = []
    for s in d.sections:
        e = s._sectPr.find(qn('w:pgNumType'))
        fmts.append(None if e is None else e.get(qn('w:fmt')))
    if fmts != ['lowerRoman', 'decimal']:
        fail('V08', 'page numbering: %s (expected lowerRoman, decimal)'
             % fmts)
    else:
        ok('V08', 'pages i, ii, iii... in front, 1, 2, 3... in the body')

    # -- V09 Word fields ---------------------------------------------------
    z = zipfile.ZipFile(DOCX)
    xml = z.read('word/document.xml').decode('utf-8')
    foot = ''.join(z.read(n).decode('utf-8') for n in z.namelist()
                   if re.match(r'word/footer\d+\.xml$', n))
    for instr, name, where in ((r'TOC \\o "1-3"', config.s('toc'), xml),
                               (r'Image Caption,1',
                                config.s('list_figures'), xml),
                               (r'Table Caption,1',
                                config.s('list_tables'), xml),
                               (r'\bPAGE\b', 'page number', foot)):
        if not re.search(instr, where):
            fail('V09', 'field missing: %s' % name)
    if not re.search(r'w:dirty="true"', xml):
        fail('V09', 'the fields are not marked "dirty" — Word will not '
                    'refresh them by itself')
    if not [f for f in FAIL if f.startswith('V09')]:
        ok('V09', 'the TOC and PAGE fields exist and are marked "dirty"')

    # -- V10 mathematics as Word equations --------------------------------
    nmath = xml.count('<m:oMath>')
    # Only the mathematics OUTSIDE a figure can become OMML — what is
    # inside a figure becomes pixels. Comparing against the total would be
    # comparing two numbers that are not comparable.
    ntex = len(re.findall(r'(?<!\\)\$', chapter_prose())) // 2
    if nmath < ntex:
        fail('V10', 'OMML in the DOCX: %d; inline $...$ outside figures '
             'in the LaTeX: %d. Some mathematics became plain text.'
             % (nmath, ntex))
    else:
        ok('V10', '%d OMML equations — at least as many as the %d inline '
           '$...$ in the prose (an equation editor, as the Guidelines ask)'
           % (nmath, ntex))

    # -- V11 fonts ---------------------------------------------------------
    # The fonts live in styles.xml (and in docDefaults), not in
    # document.xml — which here carries NO w:ascii declaration at all.
    fonts = {}
    for part in ('word/styles.xml', 'word/document.xml'):
        try:
            px = z.read(part).decode('utf-8')
        except KeyError:
            continue
        for m in re.finditer(r'w:ascii="([^"]+)"', px):
            fonts[m.group(1)] = fonts.get(m.group(1), 0) + 1
    allowed = ('Times New Roman', 'Consolas', 'Courier New')
    bad_font = {f: n for f, n in fonts.items() if f not in allowed}
    if not fonts:
        fail('V11', 'no font declaration at all — the check is not looking '
                    'at the right file')
    elif bad_font:
        fail('V11', 'fonts outside the template: %s'
             % ', '.join('%s (%d)' % kv for kv in sorted(bad_font.items())))
    else:
        ok('V11', 'only Times New Roman (%d declarations) and a monospaced '
           'face for code' % fonts.get('Times New Roman', 0))

    # -- V12 abbreviations -------------------------------------------------
    listed = [s for s, _l, _p in A.entries()]
    shown = [p.text.split('\t')[0].strip() for p in paras
             if p.style.name == 'List Entry']
    # (a) the .docx list is the one data/abbreviations.tex defines
    if shown != listed:
        fail('V12', 'abbreviation list: %s vs %s' % (shown, listed))
    # (b) — the substantive part — every entry is used in the PROSE.
    # Without this the check compares the output against its own source
    # and says nothing.
    prose = chapter_prose()
    unused = [a for a in listed
              if not re.search(r'(?<![A-Za-z])%s(?![A-Za-z])' % re.escape(a),
                               prose)]
    if unused:
        fail('V12', 'in the list but NOT in the chapter prose (inside a '
             'figure only?): %s' % ', '.join(unused))
    if not [f for f in FAIL if f.startswith('V12')]:
        ok('V12', '%d abbreviations, every one of them used in the prose'
           % len(listed))
    pend = A.pending()
    if pend:
        warn('V12', '%d acronyms are used but have NO expansion that traces '
             'to a source we hold, so they stay out of the list: %s'
             % (len(pend), ', '.join(pend)))

    # -- length (NOT a check: it has no way to fail) ----------------------
    words = len(re.findall(r'\S+', text))
    # The Guidelines count WITHOUT the bibliography and the appendices.
    body_words = len(re.findall(r'\S+', body_text))
    front = next((i for i, p in enumerate(paras)
                  if p.style.name == 'Chapter Label'), 0)
    front_words = len(re.findall(
        r'\S+', '\n'.join(p.text for p in paras[:front])))
    INFO.append('length: %d words in total; body (chapters, without the '
                'preliminary pages and the bibliography) %d; front matter '
                '%d. The Guidelines ask for 20,000-40,000 for the WHOLE '
                'thesis, excluding the bibliography.'
                % (words, body_words - front_words, front_words))

    # Abstract and keywords: numbers that change with every revision, so
    # the tool prints them instead of them being written into prose and
    # going stale. Guidelines: 250-300 words, "usually 5" keywords.
    keep_lang = config.LANG
    for lang in ('en', 'el'):
        config.select(lang)
        name = config.s('abstract')
        prose, _area, kw = F.abstract()
        joined = ' '.join(prose)
        # The "[FILL IN...]" placeholder is not abstract prose. It is,
        # however, a promise of prose to come, and reporting the count
        # without saying so reads as a failure when it is a to-do.
        pending = bool(re.search(r'\[[^\]]*\]', joined))
        txt = re.sub(r'\[[^\]]*\]', ' ', joined)
        txt = re.sub(r'\\[a-zA-Z]+\s*', ' ', txt)      # \textbf, \emph, …
        txt = txt.replace('{', ' ').replace('}', ' ')
        n = len(re.findall(r'\S+', txt))
        nk = 0
        if kw and ':' in kw:
            nk = len([x for x in re.split(r'[·;]', kw.split(':', 1)[1])
                      if x.strip()])
        if 250 <= n <= 300:
            flag = ''
        elif pending and n < 250:
            flag = ('  (a marked placeholder is still counted out; the '
                    'findings that replace it should bring this inside '
                    '250-300)')
        else:
            flag = '  <-- outside 250-300'
        flagk = '' if nk == 5 else '  <-- the Guidelines say "usually 5"'
        INFO.append('%s: %d words%s; keywords %d%s'
                    % (name, n, flag, nk, flagk))
    config.select(keep_lang)

    # -- V15 no paragraph was lost -----------------------------------------
    # Every "long" word in the LaTeX must also be in the .docx. If a
    # paragraph was dropped in conversion it shows up here — a word count
    # on its own does not catch it.
    raw = ''.join(open(p, encoding='utf-8').read()
                  for p in config.chapters())
    raw = T.strip_comments(raw)
    # Figures become PNG: the drawing lives inside the image and is not
    # missing text. The CAPTION is text and stays — exactly what
    # tex2pandoc.py produced.
    raw = T.strip_figures(raw)
    for env in ('dgdata', 'dgbarchart', 'dgflow', 'tikzpicture'):
        for s0, e0, _o, _b in reversed(list(T.find_env(raw, env))):
            raw = raw[:s0] + raw[e0:]
    # The short captions \caption[...] go only into LaTeX's lists; in the
    # .docx a field fills the lists. They are checked in V16.
    raw = re.sub(r'\\caption\s*\[[^\]]*\]', r'\\caption', raw)
    body = detex(raw)
    from collections import Counter
    ctex = Counter(re.findall(LONGWORD, body))
    cdoc = Counter(re.findall(LONGWORD, chapter_docx_text(paras, d)))
    lost = {w: n - cdoc.get(w, 0) for w, n in ctex.items()
            if cdoc.get(w, 0) < n}
    if lost:
        fail('V15', '%d words of >=9 letters appear FEWER times in the '
             '.docx than in the chapters: %s'
             % (len(lost), ', '.join('%s (-%d)' % kv
                                     for kv in sorted(lost.items())[:12])))
    else:
        ok('V15', 'every word of >=9 letters in the chapters appears in the '
           '.docx at least AS MANY times (%d distinct words, %d '
           'occurrences) — no paragraph and no sentence was dropped'
           % (len(ctex), sum(ctex.values())))

    # -- V18 the document is a REAL Word document -------------------------
    # Why this check exists: if the numbers ever become typed text again,
    # everything else still passes and nobody notices — until the moment
    # someone adds a section by hand.
    numx = z.read('word/numbering.xml').decode('utf-8')
    stx = z.read('word/styles.xml').decode('utf-8')
    v18 = []

    if '[[' in text:
        v18.append('markers [[...]] were left inside the text: %s'
                   % re.findall(r'\[\[[^\]]{0,30}', text)[:5])

    # (a) a multilevel list bound to the heading styles
    m = re.search(r'<w:abstractNum w:abstractNumId="%d".*?</w:abstractNum>'
                  % WN.ABSTRACT_ID, numx, re.S)
    if not m:
        v18.append('the multilevel heading list is missing')
    else:
        want_txt = ['', '%1.%2', '%1.%2.%3']
        got_txt = re.findall(r'<w:lvlText w:val="([^"]*)"/>', m.group(0))[:3]
        if got_txt != want_txt:
            v18.append('numbering format: %s (expected %s)'
                       % (got_txt, want_txt))
    for sid, ilvl in (('Heading1', 0), ('Heading2', 1), ('Heading3', 2)):
        ms = re.search(r'w:styleId="%s".*?</w:style>' % sid, stx, re.S)
        blob = ms.group(0) if ms else ''
        if ('<w:numId w:val="%d"/>' % WN.NUM_ID) not in blob \
                or ('<w:ilvl w:val="%d"/>' % ilvl) not in blob:
            v18.append('style %s is not bound to the list (level %d)'
                       % (sid, ilvl))

    # (b) every REF points at a bookmark that exists
    marks = set(re.findall(r'<w:bookmarkStart[^>]*w:name="([^"]+)"', xml))
    refs = re.findall(r'REF\s+(\S+)', xml)
    dangling = sorted(set(r for r in refs if r not in marks))
    if dangling:
        v18.append('REF references to a bookmark that does not exist: %s'
                   % dangling[:6])

    # (c) counts: as many fields as the source has references
    n_ref_src = len(re.findall(r'\\ref\{', chapter_prose()))
    if len(refs) < n_ref_src:
        v18.append('REF fields: %d, while the LaTeX has %d \\ref'
                   % (len(refs), n_ref_src))
    n_seq = len(re.findall(r'SEQ\s+%s\d' % WN.SEQ('figure'), xml)) \
        + len(re.findall(r'SEQ\s+%s\d' % WN.SEQ('table'), xml))
    n_cap = len(man['figures']) + len(man['tables'])
    if n_seq != n_cap:
        v18.append('captions with SEQ: %d, while figures+tables: %d'
                   % (n_seq, n_cap))

    if v18:
        for b in v18:
            fail('V18', b)
    else:
        ok('V18', 'no number is typed: headings on a multilevel list, %d '
           'captions with SEQ, %d REF references to bookmarks — they '
           'renumber themselves with F9' % (n_seq, len(refs)))

    # -- V19 the ORDER of text and equations ------------------------------
    # Why it exists: one version of the converter put ALL the text of a
    # paragraph after its equations. Every word was present, no count
    # broke, and the sentence read backwards. Here the SEQUENCE is
    # compared: each equation becomes one symbol on both sides.
    MATH = '\u2588'
    # Floats are excluded from both sides. A figure and a table are
    # placed by the typesetter, not by the order of the prose: LaTeX puts
    # the whole caption before the table, while the .docx puts its
    # continuation after it, on purpose, so that the list of tables shows
    # the short form. Comparing them here would report that deliberate
    # difference as damage on every run. That the captions are complete
    # is V02 and V03; that no word was lost is V15. THIS check is about
    # one thing only: does the prose still read in the order it was
    # written.
    FLOAT_STYLES = ('Image Caption', 'Table Caption', 'Caption Cont',
                    'Table Note', 'Captioned Figure', 'Figure')
    stream = []
    started = False
    for p in paras:
        if p.style.name == 'Chapter Label':
            started = True
        if not started:
            continue
        if norm(p.text) == config.s('bibliography'):
            break
        if p.style.name in FLOAT_STYLES:
            continue
        for tok in re.finditer(r'<m:oMath>|<w:t[^>]*>([^<]*)</w:t>',
                               p._p.xml):
            stream.append(MATH if tok.group(0).startswith('<m:oMath')
                          else (tok.group(1) or ''))
    # WITH the spaces: a sequence of words needs word boundaries.
    docx_seq = re.sub(r'\s+', ' ', ' '.join(stream))

    tex = ''.join(T.strip_comments(open(p, encoding='utf-8').read())
                  for p in config.chapters())
    tex = T.strip_figures(tex, keep_caption=False)
    for env in ('table', 'dgdata', 'dgbarchart', 'dgflow', 'tikzpicture'):
        for s0, e0, _o, _b in reversed(list(T.find_env(tex, env))):
            tex = tex[:s0] + tex[e0:]
    tex = re.sub(r'\$[^$]*\$', MATH, tex)
    tex_seq = re.sub(r'\s+', ' ', detex_keepmath(tex, MATH))

    # The comparison is over a SEQUENCE OF SYMBOLS, not characters: every
    # long word and every equation becomes one symbol. Small differences
    # in spelling make no noise, while any movement shows immediately —
    # and the sequence is around a thousand symbols, so the full
    # comparison runs instantly.
    tok = re.compile(LONGWORD + '|' + MATH)

    def symbols(seq):
        return tok.findall(seq)

    tex_sym = symbols(tex_seq)
    doc_sym = symbols(docx_seq)
    vocab = {}
    def enc(ws):
        return ''.join(chr(0xE000 + vocab.setdefault(w, len(vocab)))
                       for w in ws)
    a, b = enc(tex_sym), enc(doc_sym)

    import difflib
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    ratio = sm.ratio()
    # The threshold is EXACTLY 1: today every symbol matches, so anything
    # less means something moved.
    if ratio < 1.0:
        rev = {v: k for k, v in vocab.items()}
        diff = None
        for op, i1, i2, j1, j2 in sm.get_opcodes():
            if op == 'equal':
                continue
            diff = ('%s: LaTeX «%s» / DOCX «%s»'
                    % (op,
                       ' '.join(rev[ord(c) - 0xE000] for c in a[i1:i2])[:70],
                       ' '.join(rev[ord(c) - 0xE000] for c in b[j1:j2])[:70]))
            break
        fail('V19', 'the sequence of words and equations diverges '
             '(similarity %.4f)\n      %s' % (ratio, diff))
    else:
        ok('V19', '%d symbols (long words and equations) in the SAME order '
           'as the LaTeX — similarity %.4f; no sentence reads backwards'
           % (len(doc_sym), ratio))

    # -- V20 the numbers a reader sees BEFORE pressing F9 -----------------
    # The whole design rests on one sentence in the README: "every field
    # carries the number LaTeX computed as its cached result, so the
    # document reads correctly before anyone presses F9." Nothing checked
    # it. V18 proves each REF points at a bookmark that exists; it does
    # not look at the number displayed. V15 and V19 tokenise on long words
    # and equations, so a number is invisible to both. A cached "9.9"
    # where LaTeX says "1.2" passed every other check.
    v20, n20 = [], 0
    for m in re.finditer(r'REF\s+(\S+?)(\s+\\r)?\s+\\h\s*'
                         r'</w:instrText>.*?<w:fldChar w:fldCharType="separate"/>'
                         r'.*?<w:t[^>]*>([^<]*)</w:t>', xml, re.S):
        name, shown = m.group(1), m.group(3)
        n20 += 1
        # The bookmark name is the label with every non-ASCII-alphanumeric
        # character replaced, so recover the label by matching the shape.
        want = None
        for key, val in labels.items():
            if WN.safe_bookmark_name(key) == name:
                want = val[0]
                break
        if want is None:
            v20.append('%s: no label produces this bookmark name' % name)
        elif want != shown:
            v20.append('%s: the .docx shows "%s", LaTeX computed "%s"'
                       % (name, shown, want))
    if n20 == 0:
        fail('V20', 'no REF field carries a cached result — the .docx opens '
                    'with blank cross-references')
    elif v20:
        for x in v20[:8]:
            fail('V20', 'cross-reference reads wrong before F9 — ' + x)
    else:
        ok('V20', '%d cross-references show the number LaTeX computed, '
           'before anyone presses F9' % n20)

    # -- V16 short captions: the one difference that is NOT bridged -------
    long_list = man.get('long_list', [])
    if long_list:
        warn('V16', 'in %d captions the short form is not the opening of '
             'the long one, so the .docx LISTS show the long caption where '
             "the PDF's show the short one: %s. Make the short caption the "
             'first sentence of the long one and the pipeline splits them '
             'by itself, and the difference disappears.'
             % (len(long_list), ', '.join(long_list)))
    else:
        ok('V16', 'every list shows the short caption, as the PDF does')

    # -- V14 comparison against the PDF -----------------------------------
    pdf = config.pdf_file()
    if os.path.exists(pdf):
        try:
            out = subprocess.run(['pdftotext', pdf, '-'],
                                 capture_output=True, text=True).stdout
            wtex = len(re.findall(r'\S+', out))
            drift = abs(wtex - words) / max(wtex, 1)
            msg = ('words: PDF %d, .docx %d (difference %.0f%%). A small '
                   'difference is expected: the mathematics is OMML and is '
                   'not counted as text, and the lists may show long '
                   'captions instead of short ones (V16).'
                   % (wtex, words, 100 * drift))
            (ok if drift < 0.25 else warn)('V14', msg)
        except Exception as e:                       # pragma: no cover
            warn('V14', 'no comparison against the PDF: %s' % e)
    else:
        warn('V14', 'no %s — length not compared'
             % os.path.basename(pdf))

    # -- the report --------------------------------------------------------
    for line in OK:
        print('  ok    %s' % line)
    for line in INFO:
        print('  --    %s' % line)
    for line in WARN:
        print('  WARN  %s' % line)
    for line in FAIL:
        print('  FAIL  %s' % line)
    print('\n[%s] %d ok, %d warnings, %d failures  (and %d measurements '
          'with no pass/fail criterion)'
          % (config.LANG, len(OK), len(WARN), len(FAIL), len(INFO)))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
