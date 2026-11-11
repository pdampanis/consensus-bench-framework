#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""The page order of the two editions must be the same page order.

tools/parity.py compares the CHAPTERS. Nothing compared main-en.tex
against main-el.tex, and that is the one file the README calls the place
where the page order lives. Add a chapter to one and not the other and
every source-side check stayed green while the two PDFs differed by a
whole chapter.

  M01  the same sequence of structural commands in both files
  M02  a chapter file that exists in content/<lang>/ but is not included
  M03  a chapter that is included but whose file does not exist
  M04  a front-matter file present in one edition and not the other
  M05  a leftover .aux from a file that no longer exists (it poisons the
       .docx build with "LaTeX knows it, the .docx does not" — LaTeX does
       not know it; a dead file does)

    python3 tools/mainparity.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

STRUCTURE = re.compile(
    r'\\(?:include|input)\{(content|frontmatter)/(en|el)/([A-Za-z0-9-]+)\}'
    r'|\\(tableofcontents|listoffigures|listoftables|bibliography'
    r'|blankleaf|cleardoublepage|pagenumbering)\b')


def strip_comments(s):
    return re.sub(r'(?<!\\)%.*', '', s)


def skeleton(lang):
    """The structural spine of main-<lang>.tex, language removed.

    What is compared is the SHAPE: "include the cover, a blank leaf, the
    title page, ...". The language of each path is dropped, so the two
    files compare even though every path in them differs.
    """
    path = os.path.join(ROOT, 'main-%s.tex' % lang)
    src = strip_comments(open(path, encoding='utf-8').read())
    src = src[src.index('\\begin{document}'):]
    out = []
    for m in STRUCTURE.finditer(src):
        if m.group(1):
            # frontmatter/el/abstract read into the English edition is the
            # bilingual summary the Guidelines ask for, and it is the one
            # place the two files legitimately name the other language.
            other = '' if m.group(2) == lang else '@other'
            out.append('%s/%s%s' % (m.group(1), m.group(3), other))
        else:
            out.append(m.group(4))
    return out


def included(lang):
    return set(s.split('/')[1].replace('@other', '')
               for s in skeleton(lang) if s.startswith('content/'))


def on_disk(sub, lang):
    d = os.path.join(ROOT, sub, lang)
    if not os.path.isdir(d):
        return set()
    return set(f[:-4] for f in os.listdir(d) if f.endswith('.tex'))


def main():
    bad = []

    a, b = skeleton('en'), skeleton('el')
    if a != b:
        n = min(len(a), len(b))
        i = next((i for i in range(n) if a[i] != b[i]), n)
        bad.append('M01 the two main files have different page order at '
                   'position %d: main-en.tex has "%s", main-el.tex has "%s"'
                   % (i + 1,
                      a[i] if i < len(a) else '(nothing more)',
                      b[i] if i < len(b) else '(nothing more)'))
    for lang in ('en', 'el'):
        inc, disk = included(lang), on_disk('content', lang)
        for name in sorted(disk - inc):
            bad.append('M02 content/%s/%s.tex exists but main-%s.tex does '
                       'not \\include it — it is in every check and in no '
                       'PDF' % (lang, name, lang))
        for name in sorted(inc - disk):
            bad.append('M03 main-%s.tex includes content/%s/%s, which does '
                       'not exist' % (lang, lang, name))

    for sub in ('content', 'frontmatter'):
        en, el = on_disk(sub, 'en'), on_disk(sub, 'el')
        only_en, only_el = sorted(en - el), sorted(el - en)
        if only_en or only_el:
            bad.append('M04 %s/: only EN has %s, only EL has %s'
                       % (sub, only_en or 'nothing', only_el or 'nothing'))

    for sub in ('content', 'frontmatter'):
        for lang in ('en', 'el'):
            d = os.path.join(ROOT, sub, lang)
            if not os.path.isdir(d):
                continue
            for f in sorted(os.listdir(d)):
                if f.endswith('.aux') and not os.path.exists(
                        os.path.join(d, f[:-4] + '.tex')):
                    bad.append('M05 %s/%s/%s is left over from a file that '
                               'no longer exists — delete it, or the .docx '
                               'build will report a chapter that is not '
                               'there' % (sub, lang, f))

    print('page order: %d structural steps, %d chapters per edition'
          % (len(a), len(included('en'))))
    for x in bad:
        print('  FAIL  ' + x)
    if not bad:
        print('the two editions have the same page order, the same '
              'chapters and the same front matter')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
