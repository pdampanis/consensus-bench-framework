#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Assembles the final thesis-<lang>.docx.

    front matter (python-docx)  +  body (pandoc)
  + bibliography (from thesis-<lang>.bbl)  =  build/thesis-<lang>.docx

What it guarantees:
  * the ORDER of the preliminary pages is the department template's;
  * the NUMBERING of chapters and sections is checked AGAINST LaTeX's own
    .aux files — one disagreement and the build STOPS;
  * page numbers i, ii, iii... in the front matter and 1, 2, 3... from
    Chapter 1, using two Word sections;
  * the table of contents and the lists are Word FIELDS (F9 updates them);
  * every table gets the outer border the Guidelines require.

    python3 docx/assemble.py en
"""
import os
import re
import subprocess
import sys

import docx
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_BREAK
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
from docx.shared import Cm, Pt

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import texparse as T
import wordnative as WN
from tex2pandoc import CAPCONT, TABLENOTE
import frontmatter as F
import abbreviations as A

ROOT = T.ROOT
BUILD = config.BUILD

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


def front_docx():
    return os.path.join(BUILD, 'front-%s.docx' % config.LANG)


def bib_docx():
    return os.path.join(BUILD, 'bib-%s.docx' % config.LANG)


def numbered_docx():
    return os.path.join(BUILD, 'body-numbered-%s.docx' % config.LANG)


# ---------------------------------------------------------------------------
# Small OOXML helpers
# ---------------------------------------------------------------------------
def el(tag, **attrs):
    e = OxmlElement('w:' + tag)
    for k, v in attrs.items():
        e.set(qn('w:' + k), str(v))
    return e


def field(par, instr, placeholder):
    """A Word field, marked "dirty" so it refreshes when the file opens."""
    r1 = par.add_run()
    r1._r.append(el('fldChar', fldCharType='begin', dirty='true'))
    r2 = par.add_run()
    t = OxmlElement('w:instrText')
    t.set(qn('xml:space'), 'preserve')
    t.text = instr
    r2._r.append(t)
    r3 = par.add_run()
    r3._r.append(el('fldChar', fldCharType='separate'))
    par.add_run(placeholder)
    r5 = par.add_run()
    r5._r.append(el('fldChar', fldCharType='end'))
    return par


def para(doc, text='', style='Front Body'):
    p = doc.add_paragraph(style=style)
    if text:
        p.add_run(text)
    return p


def rich(doc, text, style='Front Body'):
    r"""One paragraph out of the LaTeX.

    Two markers arrive from the .tex files:
      * \begin{center} -> F.CENTER at the start -> centred;
      * \hfill at the start -> right aligned (a signature, a place).
    So the alignment is not written twice, once per edition.
    """
    if text.startswith(F.CENTER):
        text, style = text[len(F.CENTER):], 'Front Center'
    elif text.lstrip().startswith('\\hfill'):
        style = 'Front Right'
    p = doc.add_paragraph(style=style)
    for chunk, fmt in F.inline_runs(text):
        r = p.add_run(chunk)
        r.bold = fmt.get('bold') or None
        r.italic = fmt.get('italic') or None
        if fmt.get('mono'):
            r.font.name = 'Consolas'
    return p


def page_break(doc):
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


def blank_leaf(doc):
    """A blank leaf, as the template has, carrying its own marking."""
    page_break(doc)
    para(doc, config.s('blank_leaf'), 'Blank Leaf')
    page_break(doc)


# ---------------------------------------------------------------------------
# 1. The preliminary pages
# ---------------------------------------------------------------------------
def build_front():
    doc = docx.Document(config.reference_docx())
    body = doc.element.body
    for child in list(body):
        if child.tag != qn('w:sectPr'):
            body.remove(child)

    f = F.facts()

    # ---- Cover ----------------------------------------------------------
    # The institution's emblem, as on the template's cover and in
    # frontmatter/<lang>/cover.tex. The same file in both editions.
    if os.path.exists(F.LOGO):
        lp = doc.add_paragraph(style='Front Center')
        lp.add_run().add_picture(F.LOGO, width=Cm(2.6))
    else:
        raise SystemExit('missing %s' % F.LOGO)
    for line in (f['University'], f['School'], f['Department']):
        para(doc, line, 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, config.s('thesis_kind'), 'Cover Mid')
    para(doc, '', 'Front Center')
    para(doc, f['Title'], 'Cover Big')
    para(doc, f['TitleOther'], 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, f['Author'], 'Cover Mid')
    para(doc, config.s('am') + f['AM'], 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, config.s('supervisor') + ' ' + f['Supervisor'], 'Cover Small')

    blank_leaf(doc)

    # ---- Title page -----------------------------------------------------
    para(doc, config.s('thesis_kind'), 'Cover Mid')
    para(doc, '', 'Front Center')
    para(doc, f['Title'], 'Cover Big')
    para(doc, f['TitleOther'], 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, f['Author'], 'Cover Mid')
    para(doc, config.s('am') + f['AM'], 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, config.s('supervisor'), 'Cover Small')
    para(doc, f['Supervisor'], 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, config.s('committee'), 'Cover Small')
    for member in (f['ExamA'], f['ExamB']):
        para(doc, member, 'Cover Small')
    para(doc, '', 'Front Center')
    para(doc, f['ExamDate'], 'Cover Small')

    blank_leaf(doc)

    # ---- Author's declaration (verbatim from the template) --------------
    decl_head, decl_paras = F.declaration()
    para(doc, decl_head, 'Front Heading NB')
    for p in decl_paras:
        rich(doc, p)

    blank_leaf(doc)

    # ---- Acknowledgements -----------------------------------------------
    para(doc, config.s('acknowledgements'), 'Front Heading NB')
    for p in F.acknowledgements():
        rich(doc, p)

    blank_leaf(doc)

    # ---- Summary, in this edition's language and then the other ---------
    # The Guidelines want both. The text of each is read from its own
    # frontmatter/<lang>/abstract.tex, exactly as the PDF reads it.
    other = 'el' if config.LANG == 'en' else 'en'
    for lang in (config.LANG, other):
        if lang != config.LANG:
            page_break(doc)
        keep = config.LANG
        config.select(lang)
        head = config.s('abstract')
        body_paras, area, kw = F.abstract()
        config.select(keep)
        para(doc, head, 'Front Heading NB')
        for p in body_paras:
            rich(doc, p)
        # Scientific area and keywords go IMMEDIATELY after their own
        # summary ("the keywords are stated after the abstract",
        # Guidelines).
        if area:
            rich(doc, area)
        if kw:
            rich(doc, kw)

    # ---- Contents and the lists -----------------------------------------
    page_break(doc)
    para(doc, config.s('toc'), 'Front Heading NB')
    field(para(doc, '', 'Front Body'),
          # The preliminary pages belong in the contents too: \t adds
          # their style as level 1, as the template shows.
          r' TOC \o "1-3" \t "Front Heading NB,1" \h \z \u ',
          config.s('toc_hint'))

    page_break(doc)
    para(doc, config.s('list_images'), 'Front Heading NB')
    # The wording of the note comes from the .tex, not from here —
    # otherwise the same sentence would exist written twice.
    for p in F.list_of_images_note():
        rich(doc, p)

    page_break(doc)
    para(doc, config.s('list_figures'), 'Front Heading NB')
    field(para(doc, '', 'Front Body'),
          r' TOC \h \z \t "Image Caption,1" ', config.s('lof_hint'))

    page_break(doc)
    para(doc, config.s('list_tables'), 'Front Heading NB')
    field(para(doc, '', 'Front Body'),
          r' TOC \h \z \t "Table Caption,1" ', config.s('lot_hint'))

    page_break(doc)
    para(doc, config.s('abbreviations'), 'Front Heading NB')
    abbr = A.entries()
    check_abbr(abbr)
    for short, long_, _why in abbr:
        p = doc.add_paragraph(style='List Entry')
        r = p.add_run(short)
        r.bold = True
        p.add_run('\t' + long_)

    # A section break: the front matter becomes a SEPARATE Word section,
    # so that it can be numbered i, ii, iii...
    doc.add_section(WD_SECTION.NEW_PAGE)
    doc.save(front_docx())
    return doc


def chapter_prose():
    """The chapter text WITHOUT the pictures.

    Anything inside a diagram environment becomes an image: the reader
    sees it, but it is not text in either edition. For a question like
    "is this term actually used?", only the prose counts.
    """
    src = ''.join(T.strip_comments(open(p, encoding='utf-8').read())
                  for p in config.chapters())
    src = T.strip_figures(src)
    # The short caption \caption[...] repeats the opening of the long
    # one; the converter emits that text once. Counting it twice made
    # the source look as if it held two equations more than the .docx.
    src = re.sub(r'\\caption\s*\[[^\]]*\]', r'\\caption', src)
    for env in ('dgdata', 'dgbarchart', 'dgflow', 'tikzpicture'):
        for s0, e0, _o, _b in reversed(list(T.find_env(src, env))):
            src = src[:s0] + src[e0:]
    return src


def check_abbr(abbr):
    """No abbreviation goes in the list unless the prose uses it."""
    prose = chapter_prose()
    missing = [s for s, _l, _p in abbr
               if not re.search(r'(?<![A-Za-z])%s(?![A-Za-z])' % re.escape(s),
                                prose)]
    if missing:
        raise SystemExit('ABBREVIATIONS: not present in the chapter prose '
                         '(inside a figure only?): %s' % ', '.join(missing))


# ---------------------------------------------------------------------------
# 2. Heading numbers — and the check against the .aux files
# ---------------------------------------------------------------------------
def number_headings(doc, labels, chapter_keys=None):
    """Writes the "CHAPTER n" line and checks the order of the headings.

    It no longer writes numbers into the text: 1.1 and 1.1.1 come from
    Word's multilevel list, bound to the styles Heading 2/3 (see
    wordnative.py). Add a section by hand and everything renumbers
    itself — which is what the Guidelines ask for.

    The chapter number sits on a line of its own ("CHAPTER 1"), as in the
    template, in a SEQ field so that it too counts by itself.
    """
    ch = sec = sub = 0
    produced = []
    for p in list(doc.paragraphs):
        st = p.style.name
        if st == 'Heading 1':
            ch += 1
            sec = sub = 0
            produced.append(('chapter', str(ch), p.text))
            lbl = p.insert_paragraph_before('', style='Chapter Label')
            lbl.paragraph_format.page_break_before = True
            lbl._p.append(WN.text_run(config.s('chapter') + ' '))
            key = (chapter_keys or {}).get(p._p)
            bs, be = WN.bookmark(WN.safe_bookmark_name(key) if key
                                 else 'Chap%d' % ch)
            lbl._p.append(bs)
            for e in WN.field_runs(' SEQ %s \\* ARABIC ' % WN.SEQ('chapter'),
                                   str(ch)):
                lbl._p.append(e)
            lbl._p.append(be)
            p.paragraph_format.page_break_before = False
        elif st == 'Heading 2':
            sec += 1
            sub = 0
            produced.append(('section', '%d.%d' % (ch, sec), p.text))
        elif st == 'Heading 3':
            sub += 1
            produced.append(('subsection', '%d.%d.%d' % (ch, sec, sub),
                             p.text))
    verify_numbers(produced, labels)
    return produced


def verify_numbers(produced, labels):
    """Compare with the .aux: every number LaTeX knows must correspond to
    a heading at the SAME position in the sequence.

    The number is no longer visible in the text (Word puts it there), but
    the POSITION of each heading determines it uniquely — and the
    position is what is checked."""
    ours = {}
    for kind, num, _title in produced:
        ours.setdefault(num, kind)
    bad = []
    for name, val in labels.items():
        num = val[0]
        if name.startswith(('sec:', 'ch:', 'subsec:')) and num:
            if num not in ours:
                bad.append('%s -> %s (LaTeX knows it, the .docx does not)'
                           % (name, num))
    if bad:
        raise SystemExit('NUMBERING DISAGREES:\n  ' + '\n  '.join(bad))


# ---------------------------------------------------------------------------
# 3. Tables: the outer border the Guidelines require
# ---------------------------------------------------------------------------
def marked_style(doc, marker, style):
    """Paragraphs starting with the marker take the style and lose it."""
    n = 0
    for p in doc.paragraphs:
        if not p.text.startswith(marker):
            continue
        if not p.runs:                       # a paragraph with no runs
            continue
        n += 1
        p.style = doc.styles[style]
        p.runs[0].text = p.runs[0].text[len(marker):].lstrip()
    return n


def apply_word_fields(doc):
    """Turns the tex2pandoc markers into FIELDS and BOOKMARKS.

    Afterwards no number in the document is typed:
      * captions count with SEQ, restarting in each chapter;
      * cross-references point with REF at a bookmark;
      * every one of them carries the result LaTeX computed as its cached
        value, so the document reads correctly before anyone presses F9.

    Returns (counts, chapter map).
    """
    counts = {'L': 0, 'R': 0, 'C': 0}
    chapter_keys = {}

    for p in doc.paragraphs:
        st = p.style.name
        ends = []

        def build(kind, args, rpr, _st=st, _p=p, _ends=ends):
            if kind == 'L':
                counts['L'] += 1
                key = args[0]
                if _st == 'Heading 1':
                    # The chapter's bookmark goes around its NUMBER, on
                    # the "CHAPTER n" line — that is where "see Chapter 2"
                    # points. The title carries no number.
                    chapter_keys[_p._p] = key
                    return []
                bs, be = WN.bookmark(WN.safe_bookmark_name(key))
                _ends.append(be)
                return [bs]

            if kind == 'R':
                counts['R'] += 1
                key, val = args[0], args[1]
                name = WN.safe_bookmark_name(key)
                # For a heading we ask for the paragraph NUMBER (\r); for
                # a caption the bookmark already holds only the number.
                sw = ' \\r' if key.split(':')[0] in ('sec', 'subsec') else ''
                return WN.field_runs(' REF %s%s \\h ' % (name, sw), val, rpr)

            # kind == 'C': a caption
            counts['C'] += 1
            what, key, num = args[0], args[1], args[2]
            word = config.s('figure') if what == 'F' else config.s('table')
            seq = WN.SEQ('figure') if what == 'F' else WN.SEQ('table')
            chap, _, idx = num.partition('.')
            bs, be = WN.bookmark(WN.safe_bookmark_name(key))
            out = [WN.text_run(word + ' ', rpr), bs]
            out += WN.field_runs(' SEQ %s \\c ' % WN.SEQ('chapter'), chap, rpr)
            out.append(WN.text_run('.', rpr))
            # One counter PER CHAPTER ("FigEN1", "FigEN2") instead of one
            # with "\s 1" (reset at every Heading 1): LibreOffice ignores
            # "\s" and chapter 2's figures came out 2.4, 2.5, 2.6 instead
            # of 2.1, 2.2, 2.3. A separate name works everywhere.
            out += WN.field_runs(' SEQ %s%s \\* ARABIC ' % (seq, chap),
                                 idx, rpr)
            out.append(be)
            out.append(WN.text_run(': ', rpr))
            return out

        WN.rebuild_with_markers(p._p, build)
        for be in ends:
            p._p.append(be)

    return counts, chapter_keys


def caption_continuations(doc):
    r"""Paragraphs starting with [[CAPCONT]] continue a caption.

    They take the style "Caption Cont", which the list field does NOT
    collect — so the list of figures shows the short caption, exactly as
    LaTeX's \listoffigures does.
    """
    return marked_style(doc, CAPCONT, 'Caption Cont')


def frame_tables(doc):
    for t in doc.tables:
        tblPr = t._tbl.tblPr
        for old in tblPr.findall(qn('w:tblBorders')):
            tblPr.remove(old)
        b = el('tblBorders')
        for edge in ('top', 'left', 'bottom', 'right'):
            b.append(el(edge, val='single', sz='8', space='0', color='000000'))
        for edge in ('insideH', 'insideV'):
            b.append(el(edge, val='none', sz='0', space='0'))
        tblPr.append(b)
    return len(doc.tables)


# ---------------------------------------------------------------------------
# 4. The bibliography, out of thesis-<lang>.bbl
# ---------------------------------------------------------------------------
def build_bib():
    src = open(config.bbl_file(), encoding='utf-8').read()
    chunks = re.split(r'\\bibitem\{[^}]*\}', src)[1:]
    # Our own no-ops instead of IEEEtran's \providecommand: those run
    # over several lines and cannot be cut out safely.
    out = [r'\documentclass{report}', r'\usepackage{graphicx}',
           r'\providecommand{\BIBforeignlanguage}[2]{#2}',
           r'\providecommand{\bibinfo}[2]{#2}',
           r'\providecommand{\BIBentrySTDinterwordspacing}{}',
           r'\providecommand{\BIBentryALTinterwordspacing}{}',
           r'\providecommand{\BIBentryALTinterwordstretchfactor}{}',
           r'\begin{document}',
           r'\chapter*{%s}' % config.s('bibliography')]
    for i, ch in enumerate(chunks, 1):
        ch = re.sub(r'\\end\{thebibliography\}.*', '', ch, flags=re.S)
        ch = ch.replace('\\newblock', ' ')
        ch = re.sub(r'\\BIBentry[A-Za-z]*', '', ch)
        # IEEEtran writes elastic space as "\hskip ... plus ... minus ...
        # \relax". Pandoc does not know it and printed it VERBATIM inside
        # the reference:
        #   "(PRDC 2023).1em plus 0.5em minus 0.4emIEEE, 2023"
        ch = re.sub(r'\\hskip\b[^\\]*(\\relax)?', ' ', ch)
        ch = ' '.join(ch.split())
        # MIND THE "{}": without it, when a reference begins with a braced
        # group (a corporate author such as "{Apache Kafka}"), pandoc
        # passes it as an ARGUMENT to \quad and the author disappears.
        out.append('\n[%d]\\quad{}~%s\n' % (i, ch))
    out.append(r'\end{document}')
    tex = os.path.join(BUILD, 'bib-%s.tex' % config.LANG)
    open(tex, 'w', encoding='utf-8').write('\n'.join(out))
    subprocess.run(['pandoc', tex, '-f', 'latex', '-t', 'docx',
                    '--reference-doc=' + config.reference_docx(),
                    '-o', bib_docx()], check=True)
    d = docx.Document(bib_docx())
    for p in d.paragraphs:
        if not p.text.strip() or p.text.strip() == config.s('bibliography'):
            continue
        pf = p.paragraph_format
        pf.left_indent = Cm(1.0)
        pf.first_line_indent = Cm(-1.0)
        pf.space_after = Pt(6)
    d.save(bib_docx())
    return len(chunks)


# ---------------------------------------------------------------------------
# 5. Composition + page numbers
# ---------------------------------------------------------------------------
def page_numbers(path):
    """Two sections: i, ii, iii... in front, 1, 2, 3... in the body."""
    d = docx.Document(path)
    secs = d.sections
    if len(secs) < 2:
        raise SystemExit('expected >=2 sections, found %d' % len(secs))
    for i, s in enumerate(secs):
        s.different_first_page_header_footer = (i == 0)
        f = s.footer
        f.is_linked_to_previous = False
        p = f.paragraphs[0] if f.paragraphs else f.add_paragraph()
        for r in list(p.runs):
            r._r.getparent().remove(r._r)
        p.style = d.styles['Footer']
        field(p, ' PAGE ', '1')
        sectPr = s._sectPr
        for old in sectPr.findall(qn('w:pgNumType')):
            sectPr.remove(old)
        sectPr.append(el('pgNumType',
                         fmt='lowerRoman' if i == 0 else 'decimal',
                         start='1'))
    d.save(path)
    return len(secs)


def main():
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    labels = T.read_labels()
    if not labels:
        raise SystemExit('no .aux files — run "make" first')

    print('[%s] 1. front matter...' % config.LANG)
    build_front()

    print('[%s] 2. heading numbers + table borders...' % config.LANG)
    d = docx.Document(config.body_docx())
    counts, chapter_keys = apply_word_fields(d)
    produced = number_headings(d, labels, chapter_keys)
    ncap = caption_continuations(d)
    nnote = marked_style(d, TABLENOTE, 'Table Note')
    ntab = frame_tables(d)
    d.save(numbered_docx())
    print('   chapters/sections/subsections: %d   framed tables: %d'
          '   caption continuations: %d   table notes: %d'
          % (len(produced), ntab, ncap, nnote))
    print('   Word fields — bookmarks %d, REF references %d, SEQ captions %d'
          % (counts['L'], counts['R'], counts['C']))

    print('[%s] 3. bibliography...' % config.LANG)
    nbib = build_bib()
    print('   entries: %d' % nbib)

    print('[%s] 4. composing...' % config.LANG)
    from docxcompose.composer import Composer
    master = Composer(docx.Document(front_docx()))
    master.append(docx.Document(numbered_docx()))
    master.append(docx.Document(bib_docx()))
    master.save(config.out_docx())

    # docxcompose scrambles the bookmark ids; without this the references
    # point at the wrong stretch of text.
    dfix = docx.Document(config.out_docx())
    nfix, ntot = WN.fix_bookmarks(dfix)
    dfix.save(config.out_docx())
    print('   bookmarks: %d, repaired %d mismatched pairs' % (ntot, nfix))

    print('[%s] 5. page numbers...' % config.LANG)
    n = page_numbers(config.out_docx())
    print('   Word sections: %d' % n)

    d = docx.Document(config.out_docx())
    print('\nwrote %s (%d bytes)'
          % (config.out_docx(), os.path.getsize(config.out_docx())))
    print('paragraphs: %d   tables: %d   images: %d'
          % (len(d.paragraphs), len(d.tables), len(d.inline_shapes)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
