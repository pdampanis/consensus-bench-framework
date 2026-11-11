#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""The content of the preliminary pages, for either edition.

SOURCE OF TRUTH — everything is read from a file; nothing is written
here:
  * the facts about the thesis (title, name, registration number,
    supervisor, committee) -> frontmatter/meta.tex;
  * the author's declaration -> frontmatter/<lang>/declaration.tex;
  * abstract / acknowledgements -> the matching frontmatter/<lang>/*.tex.

WHAT IS NOT READ: the LAYOUT of the cover and the title page is written
in assemble.py, not derived from cover.tex / titlepage.tex. The CONTENT
of both pages is shared (it comes from meta.tex); the alignment and the
sizes are written twice. Change the layout in one and change it in the
other.

Anything that does NOT come from one of those sources stays a marked
placeholder. Nothing is invented.
"""
import datetime
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import texparse as T

ROOT = T.ROOT

# --------------------------------------------------------------------------
# The facts — READ from frontmatter/meta.tex.
#
# They are not written here. The same file feeds the PDF, so a title or a
# name cannot change in one edition and not in the other.
# --------------------------------------------------------------------------
META = os.path.join(ROOT, 'frontmatter', 'meta.tex')


def _read_meta():
    src = T.strip_comments(open(META, encoding='utf-8').read())
    out = {}
    for m in re.finditer(r'\\newcommand\{\\(th[A-Za-z]+)\}\s*(\[\d\])?\s*\{',
                         src):
        if m.group(2):          # a macro with arguments (\thBlank, \thTODO)
            continue
        val, _ = T.match_brace(src, m.end() - 1)
        out[m.group(1)] = ' '.join(val.split())
    return out


_M = _read_meta()


def meta(key):
    """A fact from meta.tex. Keys ending EN/EL are chosen by language."""
    if 'th' + key + config.LANG.upper() in _M:
        return _M['th' + key + config.LANG.upper()]
    if 'th' + key in _M:
        return _M['th' + key]
    raise SystemExit('frontmatter/meta.tex: no \\th%s%s'
                     % (key, config.LANG.upper()))


def facts():
    """Everything the cover and the title page need, in one dict."""
    need = ['Title', 'Author', 'AM', 'Supervisor', 'University', 'School',
            'Department', 'ExamA', 'ExamB', 'ExamDate']
    d = {k: _todo(meta(k)) for k in need}
    # The examination date in the .tex is a set of rules to write on.
    # In Word they become underscores.
    d['ExamDate'] = re.sub(r'\\thBlank\{[^{}]*\}', '_' * 8,
                           d['ExamDate']).strip()
    d['TitleOther'] = _todo(_M['thTitle' + ('EL' if config.LANG == 'en'
                                            else 'EN')])
    return d


def _todo(s):
    r"""\thTODO{X} -> <X>. The marker survives into Word on purpose."""
    return re.sub(r'\\thTODO\{([^{}]*)\}', r'<\1>', s)


LOGO = os.path.join(ROOT, 'assets', 'pada-logo.png')


# --------------------------------------------------------------------------
# Pulling text out of frontmatter/<lang>/*.tex
# --------------------------------------------------------------------------
INLINE = re.compile(r'\\(emph|textbf|textit|texttt|en|enup|gr)\{')

# Commands that leave no text but DO take arguments. Without this list the
# parser dropped the command name and let the braces leak out as text:
# "\rule{1cm}{0.4pt}" came out as "{1cm}{0.4pt}".
EATEN = {'rule': 2, 'thBlank': 1, 'hspace': 1, 'vspace': 1, 'label': 1,
         'index': 1, 'phantomsection': 0, 'hfill': 0, 'par': 0,
         'clearpage': 0, 'newpage': 0, 'listoffigures': 0,
         'listoftables': 0, 'abbrlist': 0}


def inline_runs(s):
    """A small inline-LaTeX parser -> [(text, {bold, italic, mono})]."""
    runs, buf, i = [], [], 0
    stack = []

    def flush():
        if buf:
            fmt = {'bold': any(f == 'b' for f in stack),
                   'italic': any(f == 'i' for f in stack),
                   'mono': any(f == 'm' for f in stack)}
            runs.append((''.join(buf), fmt))
            del buf[:]

    while i < len(s):
        m = INLINE.match(s, i)
        if m:
            flush()
            cmd = m.group(1)
            inner, j = T.match_brace(s, m.end() - 1)
            stack.append({'emph': 'i', 'textit': 'i', 'en': 'i',
                          'textbf': 'b', 'texttt': 'm', 'enup': 'n',
                          'gr': 'n'}[cmd])
            for t, f in inline_runs(inner):
                fmt = {'bold': f['bold'] or any(x == 'b' for x in stack),
                       'italic': f['italic'] or any(x == 'i' for x in stack),
                       'mono': f['mono'] or any(x == 'm' for x in stack)}
                runs.append((t, fmt))
            stack.pop()
            i = j
            continue
        if s[i] == '\\':
            m2 = re.match(r'\\([a-zA-Z]+)\s*', s[i:])
            if m2:
                i += m2.end()
                # Swallow the arguments too, or the braces come out as text.
                for _ in range(EATEN.get(m2.group(1), 0)):
                    if i < len(s) and s[i] == '{':
                        _drop, i = T.match_brace(s, i)
                continue
            buf.append(s[i + 1] if i + 1 < len(s) else '')
            i += 2
            continue
        buf.append(s[i])
        i += 1
    flush()
    return [(t, f) for t, f in runs if t]


YEAR = str(datetime.date.today().year)

CENTER = '\x00C\x00'


def _clean(s):
    s = T.strip_comments(s)
    # Expand the meta.tex macros exactly as LaTeX does, so the two
    # documents cannot say different things.
    for name, val in _M.items():
        if re.search(r'#\d', val):
            continue
        s = re.sub(r'\\%s(?![A-Za-z])(\{\})?' % name,
                   lambda _m, v=_todo(val): v.replace('\\', ''), s)
    s = _todo(s)
    s = s.replace('\\dotfill{}', '……………').replace('\\dotfill', '……………')
    # TeX dashes: "---" is an em dash, "--" an en dash. Without this the
    # .docx printed "2--3 sentences" where the PDF printed "2-3".
    s = s.replace('---', '—').replace('--', '–')
    # \begin{center} -> a marker that assemble.py reads
    s = re.sub(r'\\begin\{center\}\s*', '\n\n' + CENTER, s)
    s = re.sub(r'\s*\\end\{center\}', '\n\n', s)
    s = re.sub(r'\\begin\{flushright\}\s*', '\n\n', s)
    s = re.sub(r'\s*\\end\{flushright\}', '\n\n', s)
    # \the\year is the current year in LaTeX; write it out, or the inline
    # parser drops it silently and the line ends "Athens,".
    s = s.replace('\\the\\year', YEAR)
    s = re.sub(r'\\frontheading\s*\{[^{}]*\}\s*\{[^{}]*\}', '', s)
    s = re.sub(r'\\(chapter|section)\*?\s*\{[^{}]*\}', '', s)
    s = re.sub(r'\\addcontentsline\s*\{[^{}]*\}\s*\{[^{}]*\}\s*\{[^{}]*\}',
               '', s)
    s = re.sub(r'\\markboth\s*\{[^{}]*\}\s*\{[^{}]*\}', '', s)
    s = re.sub(r'\\(vspace|hspace)\*?\s*\{[^{}]*\}', '', s)
    s = re.sub(r'\\begin\{(english|greek)\}|\\end\{(english|greek)\}', '', s)
    s = re.sub(r'\\noindent\s*', '', s)
    s = s.replace('\\\\', '\n\n')
    return s


def _paragraphs(s):
    out = []
    for blk in re.split(r'\n\s*\n', _clean(s)):
        blk = ' '.join(blk.split())
        if blk:
            out.append(blk)
    return out


def load(name):
    p = os.path.join(config.frontmatter_dir(), name)
    return _paragraphs(open(p, encoding='utf-8').read())


# The abstract page carries three things the template wants kept apart:
# the summary itself, the scientific area, and the keywords.
AREA = {'en': 'SCIENTIFIC AREA:', 'el': 'ΕΠΙΣΤΗΜΟΝΙΚΗ ΠΕΡΙΟΧΗ:'}
KEYWORDS = {'en': 'KEYWORDS:', 'el': 'ΛΕΞΕΙΣ ΚΛΕΙΔΙΑ:'}


def abstract():
    """(paragraphs, scientific-area line, keywords line)."""
    body, area, kw = [], None, None
    for p in load('abstract.tex'):
        if KEYWORDS[config.LANG] in p:
            kw = p
        elif AREA[config.LANG] in p:
            area = p
        else:
            body.append(p)
    return body, area, kw


def acknowledgements():
    return load('acknowledgements.tex')


def declaration():
    """(heading, paragraphs) of the author's declaration, from the .tex."""
    raw = open(os.path.join(config.frontmatter_dir(), 'declaration.tex'),
               encoding='utf-8').read()
    m = re.search(r'\\frontheading\s*\{([^{}]*)\}', T.strip_comments(raw))
    if not m:
        raise SystemExit('%s/declaration.tex: no \\frontheading'
                         % config.LANG)
    return m.group(1), _paragraphs(raw)


def list_of_images_note():
    """The one paragraph on the LIST OF IMAGES page, from lists.tex."""
    paras = load('lists.tex')
    return paras[:1]


if __name__ == '__main__':
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    f = facts()
    print('%s: %s / %s / %s' % (config.LANG, f['Title'][:48], f['Author'],
                                f['AM']))
    b, a, k = abstract()
    print('  abstract %d paragraphs, %d words; area %s; keywords %s'
          % (len(b), sum(len(p.split()) for p in b),
             'yes' if a else 'MISSING', 'yes' if k else 'MISSING'))
    h, d = declaration()
    print('  declaration "%s", %d paragraphs' % (h, len(d)))
    print('  acknowledgements %d paragraphs' % len(acknowledgements()))
