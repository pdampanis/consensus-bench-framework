#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Builds the reference .docx that carries the department's styles.

It starts from pandoc's own default reference.docx, so that EVERY style
name the converter uses exists. On top of that it writes what the
department's thesis-writing Guidelines require:

  A4; Times New Roman 11 pt; line spacing 1.5; headings 14 pt bold;
  subheadings 12 pt bold; no blank line between paragraphs (spacing
  "before" instead); tables with an outer border.

    python3 docx/make-reference.py en
"""
import os, subprocess, zipfile, sys
from lxml import etree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import wordnative as WN

HERE = config.HERE
BUILD = config.BUILD
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'


def q(t):
    return '{%s}%s' % (W, t)


def sub(parent, tag, **attrs):
    e = etree.SubElement(parent, q(tag))
    for k, v in attrs.items():
        e.set(q(k), str(v))
    return e


FONT = 'Times New Roman'
BODY_HALFPT = 22      # 11 pt, in half-points
LINE_15 = 360         # 1.5 lines (240 = single)


def _ppr_pos(style):
    """pPr must come before rPr (the OOXML schema fixes the order)."""
    for i, c in enumerate(style):
        if c.tag == q('rPr'):
            return i
    return len(style)


def set_ppr(style, *, before=None, after=None, line=None, align=None,
            keep_next=None, page_break=None, outline=None, ind_left=None,
            hanging=None, contextual=None):
    """Writes a style's <w:pPr> from scratch."""
    for old in style.findall(q('pPr')):
        style.remove(old)
    p = etree.Element(q('pPr'))
    if keep_next:
        sub(p, 'keepNext')
        sub(p, 'keepLines')
    if page_break:
        sub(p, 'pageBreakBefore')
    if ind_left is not None or hanging is not None:
        i = sub(p, 'ind')
        if ind_left is not None:
            i.set(q('left'), str(ind_left))
        if hanging is not None:
            i.set(q('hanging'), str(hanging))
    sp = sub(p, 'spacing')
    sp.set(q('before'), str(before if before is not None else 0))
    sp.set(q('after'), str(after if after is not None else 0))
    if line is not None:
        sp.set(q('line'), str(line))
        sp.set(q('lineRule'), 'auto')
    if contextual:
        sub(p, 'contextualSpacing')
    if align:
        sub(p, 'jc', val=align)
    if outline is not None:
        sub(p, 'outlineLvl', val=str(outline))
    style.insert(_ppr_pos(style), p)
    return p


def set_rpr(style, *, size=None, bold=None, italic=None, caps=None,
            font=FONT, color=None, lang=None):
    for old in style.findall(q('rPr')):
        style.remove(old)
    r = etree.SubElement(style, q('rPr'))
    f = sub(r, 'rFonts')
    for a in ('ascii', 'hAnsi', 'cs', 'eastAsia'):
        f.set(q(a), font)
    if bold:
        sub(r, 'b'); sub(r, 'bCs')
    if italic:
        sub(r, 'i'); sub(r, 'iCs')
    if caps:
        sub(r, 'caps')
    if color:
        sub(r, 'color', val=color)
    s = size if size is not None else BODY_HALFPT
    sub(r, 'sz', val=str(s))
    sub(r, 'szCs', val=str(s))
    sub(r, 'lang', val=lang or config.word_lang())
    return r


def get_style(root, sid):
    for s in root.findall(q('style')):
        if s.get(q('styleId')) == sid:
            return s
    return None


def make_style(root, sid, name, *, based='Normal', stype='paragraph',
               custom=True, qformat=True, next_=None):
    s = get_style(root, sid)
    if s is not None:
        return s
    s = etree.SubElement(root, q('style'))
    s.set(q('type'), stype)
    s.set(q('styleId'), sid)
    if custom:
        s.set(q('customStyle'), '1')
    sub(s, 'name', val=name)
    if based:
        sub(s, 'basedOn', val=based)
    if next_:
        sub(s, 'next', val=next_)
    if qformat:
        sub(s, 'qFormat')
    return s


def borders(parent, tag, *, box='single', box_sz=8, inside='single',
            inside_sz=4):
    b = sub(parent, tag)
    for side in ('top', 'left', 'bottom', 'right'):
        e = sub(b, side)
        e.set(q('val'), box); e.set(q('sz'), str(box_sz))
        e.set(q('space'), '0'); e.set(q('color'), '000000')
    for side in ('insideH', 'insideV'):
        e = sub(b, side)
        if inside is None:
            e.set(q('val'), 'none'); e.set(q('sz'), '0')
        else:
            e.set(q('val'), inside); e.set(q('sz'), str(inside_sz))
        e.set(q('space'), '0'); e.set(q('color'), '000000')
    return b


def build_styles(xml):
    root = etree.fromstring(xml)

    # ---- docDefaults: Times New Roman 11 pt ----------------------------
    dd = root.find(q('docDefaults'))
    rprd = dd.find(q('rPrDefault'))
    for c in list(rprd):
        rprd.remove(c)
    r = etree.SubElement(rprd, q('rPr'))
    f = sub(r, 'rFonts')
    for a in ('ascii', 'hAnsi', 'cs', 'eastAsia'):
        f.set(q(a), FONT)
    sub(r, 'sz', val=str(BODY_HALFPT)); sub(r, 'szCs', val=str(BODY_HALFPT))
    sub(r, 'lang', val=config.word_lang())

    # ---- Normal ---------------------------------------------------------
    n = get_style(root, 'Normal')
    set_rpr(n)
    set_ppr(n, before=120, after=0, line=LINE_15, align='both')

    for sid in ('BodyText', 'FirstParagraph', 'Compact'):
        s = get_style(root, sid)
        if s is not None:
            set_rpr(s)
            set_ppr(s, before=120, after=0, line=LINE_15, align='both')

    # ---- Headings -------------------------------------------------------
    h1 = get_style(root, 'Heading1')
    set_rpr(h1, size=28, bold=True)
    set_ppr(h1, before=240, after=240, line=LINE_15, align='center',
            keep_next=True, page_break=True, outline=0)
    # Heading numbers are NOT written as text: the style is bound here to
    # the multilevel list, so the numbers renumber themselves when you add
    # or remove a section inside Word.
    WN.bind_style_to_list(h1, 0)

    h2 = get_style(root, 'Heading2')
    set_rpr(h2, size=24, bold=True)
    set_ppr(h2, before=240, after=120, line=LINE_15, align='left',
            keep_next=True, outline=1)
    WN.bind_style_to_list(h2, 1)

    h3 = get_style(root, 'Heading3')
    set_rpr(h3, size=24, bold=True)
    set_ppr(h3, before=180, after=120, line=LINE_15, align='left',
            keep_next=True, outline=2)
    WN.bind_style_to_list(h3, 2)

    for i, sid in enumerate(['Heading4', 'Heading5', 'Heading6',
                             'Heading7', 'Heading8', 'Heading9'], start=3):
        s = get_style(root, sid)
        if s is not None:
            set_rpr(s, size=BODY_HALFPT, bold=True, italic=(i > 3))
            set_ppr(s, before=120, after=60, line=LINE_15, align='left',
                    keep_next=True, outline=i)

    # ---- Captions: LaTeX's \small (10 pt), centred --------------------
    for sid in ('Caption', 'ImageCaption', 'TableCaption'):
        s = get_style(root, sid)
        if s is None:
            continue
        set_rpr(s, size=20)
        set_ppr(s, before=60, after=180 if sid != 'TableCaption' else 60,
                line=240, align='center',
                keep_next=(sid == 'TableCaption'))

    fig = get_style(root, 'Figure')
    if fig is not None:
        set_rpr(fig)
        set_ppr(fig, before=180, after=60, line=240, align='center')
    cf = get_style(root, 'CaptionedFigure')
    if cf is not None:
        set_rpr(cf)
        set_ppr(cf, before=180, after=180, line=240, align='center')

    # ---- Bibliography: hanging indent ----------------------------------
    bib = get_style(root, 'Bibliography')
    if bib is not None:
        set_rpr(bib)
        set_ppr(bib, before=120, after=0, line=LINE_15, align='both',
                ind_left=567, hanging=567)

    # ---- Tables: the outer border the Guidelines make MANDATORY --------
    tbl = get_style(root, 'Table')
    if tbl is not None:
        set_rpr(tbl, size=20)
        for old in tbl.findall(q('tblPr')):
            tbl.remove(old)
        tp = etree.SubElement(tbl, q('tblPr'))
        borders(tp, 'tblBorders', box_sz=12, inside_sz=4)
        m = sub(tp, 'tblCellMar')
        for side, wd in (('top', 60), ('left', 108), ('bottom', 60),
                         ('right', 108)):
            e = sub(m, side); e.set(q('w'), str(wd)); e.set(q('type'), 'dxa')

    for sid in ('VerbatimChar',):
        s = get_style(root, sid)
        if s is not None:
            set_rpr(s, size=20, font='Courier New')

    # ---- Extra styles for the preliminary pages ------------------------
    specs = [
        ('CoverBig',     'Cover Big',        28, True,  'center', 0,   120, 240, False),
        ('CoverMid',     'Cover Mid',        24, True,  'center', 0,   120, 240, False),
        ('CoverSmall',   'Cover Small',      BODY_HALFPT, False, 'center', 0, 120, 240, False),
        ('FrontHeading', 'Front Heading',    28, True,  'center', 0,   360, 240, True),
        ('FrontHeadNB',  'Front Heading NB', 28, True,  'center', 0,   360, 240, False),
        ('ChapterLabel', 'Chapter Label',    28, True,  'left',   0,   0,   240, True),
        ('FrontBody',    'Front Body',       BODY_HALFPT, False, 'both', 120, 0, LINE_15, False),
        ('FrontCenter',  'Front Center',     BODY_HALFPT, False, 'center', 120, 0, LINE_15, False),
        # Caption continuation: the SAME appearance as Image Caption, a
        # DIFFERENT name. The TOC field collects only "Image Caption" and
        # "Table Caption", so the list of figures picks up the SHORT
        # caption — exactly what \listoffigures does with
        # \caption[short]{long}.
        ('TableNote',    'Table Note',       20, False, 'left',   0, 120, 240, False),
        ('CaptionCont',  'Caption Cont',     20, False, 'center', 0, 120, 240, False),
        ('FrontRight',   'Front Right',      BODY_HALFPT, False, 'right', 120, 0, LINE_15, False),
        ('BlankLeaf',    'Blank Leaf',       BODY_HALFPT, False, 'center', 0, 0, 240, True),
        ('ListEntry',    'List Entry',       BODY_HALFPT, False, 'left', 60, 0, 240, False),
    ]
    for sid, name, size, bold, align, before, after, line, pbreak in specs:
        s = make_style(root, sid, name, based='Normal', next_='Normal')
        set_rpr(s, size=size, bold=bold)
        set_ppr(s, before=before, after=after, line=line, align=align,
                page_break=pbreak,
                keep_next=(sid in ('FrontHeading', 'FrontHeadNB',
                                   'ChapterLabel')))

    # TOC styles (Word's own TOC field fills these paragraphs)
    for lvl in (1, 2, 3):
        s = make_style(root, 'TOC%d' % lvl, 'toc %d' % lvl, based='Normal',
                       custom=False, qformat=False, next_='Normal')
        set_rpr(s, size=BODY_HALFPT, bold=(lvl == 1))
        set_ppr(s, before=60, after=0, line=240, align='left',
                ind_left=(lvl - 1) * 340)

    for sid, name in (('Header', 'header'), ('Footer', 'footer')):
        s = make_style(root, sid, name, based='Normal', custom=False,
                       qformat=False, next_='Normal')
        set_rpr(s, size=20)
        set_ppr(s, before=0, after=0, line=240, align='center')

    th = get_style(root, 'TOCHeading')
    if th is not None:
        set_rpr(th, size=28, bold=True)
        set_ppr(th, before=0, after=240, line=240, align='center')

    return etree.tostring(root, xml_declaration=True, encoding='UTF-8',
                          standalone=True)


def build_numbering(xml):
    """Adds the multilevel list that numbers the headings.

    Pandoc's own lists (bullets, numbered lists) are left alone; our ids
    are 700, well clear of its. Every <w:abstractNum> must come before
    ALL the <w:num> elements.
    """
    root = etree.fromstring(xml)
    a, n = WN.heading_numbering_xml()
    nums = root.findall(q('num'))
    if nums:
        nums[0].addprevious(a)
    else:
        root.append(a)
    root.append(n)
    return etree.tostring(root, xml_declaration=True, encoding='UTF-8',
                          standalone=True)


def build_document(xml):
    """Sets A4 and 2.54 cm margins (the template's own sectPr)."""
    root = etree.fromstring(xml)
    body = root.find(q('body'))
    for old in body.findall(q('sectPr')):
        body.remove(old)
    sp = etree.SubElement(body, q('sectPr'))
    pg = sub(sp, 'pgSz'); pg.set(q('w'), '11906'); pg.set(q('h'), '16838')
    mg = sub(sp, 'pgMar')
    for k, v in (('top', 1440), ('right', 1440), ('bottom', 1440),
                 ('left', 1440), ('header', 709), ('footer', 709),
                 ('gutter', 0)):
        mg.set(q(k), str(v))
    sub(sp, 'cols', space='708')
    sub(sp, 'docGrid', linePitch='360')
    return etree.tostring(root, xml_declaration=True, encoding='UTF-8',
                          standalone=True)


def main():
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    os.makedirs(BUILD, exist_ok=True)
    base = os.path.join(BUILD, 'pandoc-ref.docx')
    if not os.path.exists(base):
        with open(base, 'wb') as fh:
            subprocess.run(['pandoc', '--print-default-data-file',
                            'reference.docx'], stdout=fh, check=True)
    out = config.reference_docx()
    zin = zipfile.ZipFile(base)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'word/styles.xml':
                data = build_styles(data)
            elif item.filename == 'word/numbering.xml':
                data = build_numbering(data)
            elif item.filename == 'word/document.xml':
                data = build_document(data)
            zo.writestr(item, data)
    print('%s: wrote %s (%d bytes)'
          % (config.LANG, os.path.basename(out), os.path.getsize(out)))


if __name__ == '__main__':
    sys.exit(main())
