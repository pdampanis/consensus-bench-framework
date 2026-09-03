#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Prepares one edition's body text for pandoc.

Never writes to content/. Reads it, transforms in memory, and writes an
intermediate build/body-<lang>.tex plus a manifest of what it found, so
the checks afterwards have something to compare against.

What it changes, and why:

  * \\dgfigure{...} becomes an \\includegraphics of the PNG that
    render-figures.py produced. Nothing is lost silently: a missing PNG
    stops the build.
  * Captions carry a MARKER instead of a number. assemble.py turns the
    marker into Word SEQ fields, so figure numbers renumber themselves
    when the author inserts a figure by hand.
  * \\ref becomes a marker too, which becomes a Word REF field.
  * \\cite becomes [n], with n read from the .bbl — the same number the
    PDF prints, because it is literally the same file.
  * \\meas{key} becomes the measured value, or the [PENDING] marker if
    the author has not run that experiment yet.

    python3 docx/tex2pandoc.py en
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
import texparse as T

# Markers that survive pandoc as plain text and become Word fields in
# assemble.py. Checked afterwards: none may remain in the .docx.
MARK_LABEL = '[[L|%s]]'
MARK_REF = '[[R|%s|%s]]'
MARK_CAP = '[[C|%s|%s|%s]]'
CAPCONT = '[[CAPCONT]]'
TABLENOTE = '[[TABLENOTE]]'

PREAMBLE = r"""\documentclass[11pt,a4paper]{report}
\usepackage{graphicx}
\usepackage{booktabs}
\usepackage{amsmath,amssymb}
\newcommand{\en}[1]{\emph{#1}}
\newcommand{\enup}[1]{#1}
\newcommand{\gr}[1]{#1}
\newcommand{\grey}{\textsuperscript{G}}
\newcommand{\prep}{\textsuperscript{P}}
\newcommand{\unver}{\textsuperscript{U}}
\newcommand{\setstretch}[1]{}
\begin{document}
"""

DROP_NOARG = ['centering', 'noindent', 'small', 'footnotesize', 'scriptsize',
              'normalsize', 'large', 'Large', 'toprule', 'midrule',
              'bottomrule', 'raggedright', 'raggedleft', 'clearpage',
              'newpage', 'par', 'phantomsection']
DROP_ONEARG = ['vspace', 'hspace', 'label', 'setstretch', 'addlinespace',
               'index', 'markboth']

# enumitem options make pandoc throw away every \item[...] label. Found
# by the "no paragraph went missing" check, after 17 definitions had
# silently vanished from the Word file.
LIST_ENVS = ('description', 'itemize', 'enumerate')


def strip_list_options(s):
    for env in LIST_ENVS:
        s = re.sub(r'(\\begin\{%s\})\s*\[[^\]]*\]' % env, r'\1', s)
    return s


def drop_commands(s):
    for c in DROP_NOARG:
        s = re.sub(r'\\%s(?![a-zA-Z])\s*' % re.escape(c), '', s)
    for c in DROP_ONEARG:
        s = re.sub(r'\\%s\*?\s*\{[^{}]*\}' % re.escape(c), '', s)
    s = re.sub(r'\\cmidrule\s*(\([^)]*\))?\s*\{[^}]*\}', '', s)
    return s


def read_args(s, start, n):
    """n brace groups beginning at s[start]; returns (args, index after)."""
    args, i = [], start
    for _ in range(n):
        while i < len(s) and s[i] in ' \n\t%':
            if s[i] == '%':
                i = s.index('\n', i)
            i += 1
        if i >= len(s) or s[i] != '{':
            raise ValueError('expected { at %d' % i)
        a, i = T.match_brace(s, i)
        args.append(a)
    return args, i


def resolve_figures(src, labels, manifest, missing_png):
    """\\dgfigure{label}{short}{long}{body} -> an image with a caption."""
    out, pos = [], 0
    for m in re.finditer(r'\\dgfigure\s*\{', src):
        if m.start() < pos:
            continue
        args, end = read_args(src, m.end() - 1, 4)
        label, short, long_, _body = args
        num = labels.get(label, ('?',))[0]
        png = os.path.join(config.figures_dir(),
                           re.sub(r'.*?\{figures/src/([^}]*)\}.*', r'\1',
                                  _body.replace('\n', ' ')) + '.png')
        if not os.path.exists(png):
            missing_png.append((label, png))
        short1 = ' '.join(short.split())
        long1 = ' '.join(long_.split())
        head, cont = split_caption('F', label, num, short1, long1)
        manifest['figures'].append({'label': label, 'number': num,
                                    'short': short1, 'caption': long1,
                                    'png': os.path.relpath(png, config.ROOT)})
        out.append(src[pos:m.start()])
        out.append('\n\\begin{figure}\n\\centering\n'
                   '\\includegraphics[width=0.92\\linewidth]{%s}\n'
                   '\\caption{%s}\n\\end{figure}\n%s' % (png, head, cont))
        pos = end
    out.append(src[pos:])
    return ''.join(out)


LONG_CAPTIONS = []


def split_caption(kind, label, num, short, long_):
    """(caption for the list, remainder as its own paragraph).

    LaTeX puts the SHORT caption in the list of figures and the LONG one
    under the figure. Word collects the WHOLE caption paragraph, so the
    only way to give the list the short form is to split the caption in
    two and style the second half so the field ignores it.

    That works when the short caption is the opening of the long one,
    which the chapters are written to guarantee. If it ever stops being
    true the figure is recorded here and the check reports it, rather
    than the build quietly changing what the page says.
    """
    head = '%s%s' % (MARK_CAP % (kind, label, num), short)
    if short and long_.startswith(short):
        rest = long_[len(short):].strip()
        return head, ('\n\n%s %s\n\n' % (CAPCONT, rest) if rest else '')
    LONG_CAPTIONS.append('%s %s' % (kind, num))
    return '%s%s' % (MARK_CAP % (kind, label, num), long_), ''



def mark_table_note(rest):
    r"""Marks EVERY paragraph of a table's note, not just the first.

    The note is a minipage under the table. It often runs to more than
    one paragraph (a \smallskip and a second bold lead-in). Marking only
    the opening left the later paragraphs styled as body text, which put
    ordinary prose between a table and the continuation of its caption —
    and the caption check then read the caption as truncated.
    """
    out, pos = [], 0
    for s0, e0, _o, inner in list(T.find_env(rest, 'minipage')):
        out.append(rest[pos:s0])
        # Drop the minipage's own width argument, then split into
        # paragraphs on blank lines and on the vertical skips that TeX
        # uses to start one.
        inner = re.sub(r'^\s*(\[[^\]]*\])?\s*\{[^{}]*\}', '', inner, count=1)
        inner = re.sub(r'\\(smallskip|medskip|bigskip)\b', '\n\n', inner)
        paras = [' '.join(x.split()) for x in re.split(r'\n\s*\n', inner)]
        out.append('\n\n' + '\n\n'.join(TABLENOTE + ' ' + x
                                          for x in paras if x) + '\n\n')
        pos = e0
    out.append(rest[pos:])
    return ''.join(out)


def resolve_tables(src, labels, manifest):
    out, pos = [], 0
    for s0, e0, _o, body in list(T.find_env(src, 'table')):
        out.append(src[pos:s0])
        lab = re.search(r'\\label\{([^}]*)\}', body)
        cap = re.search(r'\\caption\s*(\[)?', body)
        if not lab or not cap:
            raise SystemExit('table without caption/label')
        key = lab.group(1)
        i = cap.end() - (1 if cap.group(1) else 0)
        short = None
        if cap.group(1):
            j = body.index(']', i)
            short = body[i + 1:j]
            i = j + 1
        while i < len(body) and body[i] in ' \n\t':
            i += 1
        long_, ce = T.match_brace(body, i)
        num = labels.get(key, ('?',))[0]
        short1 = ' '.join((short or long_).split())
        long1 = ' '.join(long_.split())
        head, cont = split_caption('T', key, num, short1, long1)
        rest = body[:cap.start()] + body[ce:]
        rest = mark_table_note(rest)
        manifest['tables'].append({'label': key, 'number': num,
                                   'short': short1, 'caption': long1})
        out.append('\n\\begin{table}\n\\caption{%s}\n%s\n\\end{table}\n%s'
                   % (head, rest, cont))
        pos = e0
    out.append(src[pos:])
    return ''.join(out)


def mark_heading_labels(s):
    """Bookmark marker in front of every heading that carries a \\label."""
    out, pos = [], 0
    pat = re.compile(r'\\(chapter|section|subsection|subsubsection)\*?\s*'
                     r'(?:\[[^\]]*\])?\s*\{')
    for m in pat.finditer(s):
        if m.start() < pos:
            continue
        title, i = T.match_brace(s, m.end() - 1)
        m2 = re.match(r'\s*\\label\{([^}]*)\}', s[i:])
        if not m2:
            continue
        out.append(s[pos:m.end()])
        out.append(MARK_LABEL % m2.group(1))
        out.append(title)
        out.append('}')
        pos = i
    out.append(s[pos:])
    return ''.join(out)


def compress(nums):
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


def measurements():
    """{key: printed value} — a pending one prints as a loud marker."""
    path = os.path.join(config.ROOT, 'data', 'measurements.tex')
    out = {}
    for line in open(path, encoding='utf-8'):
        code = line.split('%')[0]
        m = re.search(r'\\declaremeasurement\{([^}]+)\}\{([^}]*)\}', code)
        if m:
            out[m.group(1)] = m.group(2)
            continue
        m = re.search(r'\\pendingmeasurement\{([^}]+)\}', code)
        if m:
            out[m.group(1)] = '[PENDING]'
    return out


def convert():
    del LONG_CAPTIONS[:]
    labels = T.read_labels()
    bblnum, _ = T.read_bbl_order()
    meas = measurements()
    manifest = {'lang': config.LANG, 'figures': [], 'tables': [],
                'headings': [], 'cite_keys': [], 'missing_refs': [],
                'unknown_cites': [], 'blocks': []}
    missing_refs, unknown_cites, missing_png = [], [], []
    parts = []

    for path in T.CHAPTERS():
        raw = open(path, encoding='utf-8').read()
        manifest['blocks'] += re.findall(r'^%%blk\s+(\S+)', raw, re.M)
        src = T.strip_comments(raw)

        for m in re.finditer(
                r'\\(chapter|section|subsection|subsubsection|paragraph)\*?\s*'
                r'(\[[^\]]*\])?\s*\{', src):
            title, _ = T.match_brace(src, m.end() - 1)
            manifest['headings'].append({'level': m.group(1),
                                         'title': ' '.join(title.split())})
        for m in re.finditer(r'\\cite\{([^}]*)\}', src):
            manifest['cite_keys'] += T.cite_keys(m.group(1))

        src = resolve_figures(src, labels, manifest, missing_png)
        src = resolve_tables(src, labels, manifest)
        src = strip_list_options(src)
        src = mark_heading_labels(src)

        def refrep(m):
            k = m.group(1)
            if k in labels:
                return MARK_REF % (k, labels[k][0])
            missing_refs.append(k)
            return '[[UNKNOWN REF %s]]' % k
        src = re.sub(r'\\ref\{([^}]*)\}', refrep, src)

        def citerep(m):
            nums = []
            for k in T.cite_keys(m.group(1)):
                if k in bblnum:
                    nums.append(bblnum[k])
                else:
                    unknown_cites.append(k)
            return compress(nums) if nums else '[[UNKNOWN CITATION]]'
        src = re.sub(r'\\cite\{([^}]*)\}', citerep, src)

        src = re.sub(r'\\meas\{([^}]*)\}',
                     lambda m: meas.get(m.group(1), '[UNDECLARED]'), src)
        src = re.sub(r'\\thTODO\{([^}]*)\}', r'<\1>', src)
        src = drop_commands(src)
        parts.append(src)

    if missing_png:
        raise SystemExit('missing rendered figures — run render-figures.py:\n'
                         + '\n'.join('  %s -> %s' % x for x in missing_png))

    manifest['missing_refs'] = sorted(set(missing_refs))
    manifest['unknown_cites'] = sorted(set(unknown_cites))
    manifest['cite_keys_unique'] = sorted(set(manifest['cite_keys']))
    manifest['bbl_count'] = len(bblnum)
    manifest['long_captions'] = list(LONG_CAPTIONS)

    os.makedirs(config.BUILD, exist_ok=True)
    body = PREAMBLE + '\n\n'.join(parts) + '\n\\end{document}\n'
    open(config.body_tex(), 'w', encoding='utf-8').write(body)
    json.dump(manifest, open(config.manifest(), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    print('%s: %d figures, %d tables, %d citations (%d distinct, bbl %d), '
          '%d blocks' % (config.LANG, len(manifest['figures']),
                         len(manifest['tables']), len(manifest['cite_keys']),
                         len(manifest['cite_keys_unique']), len(bblnum),
                         len(manifest['blocks'])))
    if LONG_CAPTIONS:
        print('  captions whose short form is not the opening of the long '
              'one, so the lists show the full text: %s'
              % ', '.join(LONG_CAPTIONS))
    if manifest['missing_refs']:
        print('  !! unresolved \\ref: %s' % manifest['missing_refs'],
              file=sys.stderr)
    if manifest['unknown_cites']:
        print('  !! citation keys not in the .bbl: %s'
              % manifest['unknown_cites'], file=sys.stderr)
    return manifest


if __name__ == '__main__':
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    convert()
