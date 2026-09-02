#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""explain-check — measures whether a chapter has the STRUCTURE that makes
explanation possible. It does not judge whether the text explains well;
that is a human job.

Four checks, all of them countable:

  E01  a caption shorter than --min-caption words (a label, not an
       explanation)
  E02  a float that no sentence points at
  E03  a section that opens straight into a subsection
  E04  a paragraph longer than --max-sent sentences

It reads both \\caption{} and \\dgfigure{label}{short}{long}{body}, and it
splits sentences by the rules of the language the file is written in
(content/en or content/el), because "et al." and "π.χ." are not the ends
of sentences.

    python3 tools/explain-check.py content/*/*.tex [--min-caption 15]

Exit code 1 if a WARN is found.
"""
import re, sys, json, argparse, os, collections

Finding = collections.namedtuple('Finding', 'file line code level msg')

VERB = ('verbatim', 'lstlisting', 'minted', 'Verbatim')
DRAW = ('tikzpicture', 'axis', 'groupplot', 'tabular', 'tabularx', 'array',
        'align', 'equation', 'aligned', 'matrix', 'pmatrix', 'bmatrix',
        'dgdata', 'dgbarchart', 'dgflow')

# Abbreviations that end in a full stop without ending a sentence.
ABBREV = {
    'en': ('et al.', 'e.g.', 'i.e.', 'cf.', 'vs.', 'Fig.', 'Sec.', 'Ch.',
           'Eq.', 'p.', 'pp.', 'No.', 'Prof.', 'Dr.', 'St.', 'approx.'),
    'el': ('κ.ά.', 'κ.λπ.', 'π.χ.', 'βλ.', 'σσ.', 'σ.', 'ό.π.', 'κ.ο.κ.',
           'κ.τ.λ.', 'ενδ.'),
}


def lang_of(path):
    """en unless the file sits under a directory called el."""
    parts = os.path.normpath(path).split(os.sep)
    return 'el' if 'el' in parts else 'en'


def strip_comments(t):
    return re.sub(r'(?<!\\)%.*', '', t)


def balanced(s, start):
    """The content of the { } that begins at s[start] == '{'."""
    if start >= len(s) or s[start] != '{':
        return ''
    d, i = 0, start
    while i < len(s):
        if s[i] == '{' and (i == 0 or s[i - 1] != '\\'):
            d += 1
        elif s[i] == '}' and s[i - 1] != '\\':
            d -= 1
            if d == 0:
                return s[start + 1:i]
        i += 1
    return s[start + 1:]


def read_args(s, start, n):
    args, i = [], start
    for _ in range(n):
        while i < len(s) and s[i] in ' \n\t':
            i += 1
        if i >= len(s) or s[i] != '{':
            args.append('')
            continue
        a = balanced(s, i)
        args.append(a)
        i += len(a) + 2
    return args, i


def caption_words(text):
    """Caption words, with commands, mathematics and citations removed."""
    t = re.sub(r'\\(?:cite|ref|eqref|label)\s*\{[^}]*\}', ' ', text)
    t = re.sub(r'\\[a-zA-Z@]+\*?', ' ', t)
    t = re.sub(r'\$[^$]*\$', ' ', t)
    t = re.sub(r'[{}~\\&%_^]', ' ', t)
    return [w for w in re.split(r'\s+', t) if len(w) > 1]


def env_spans(text, names):
    spans, stack = [], []
    for i, ln in enumerate(text.split('\n'), 1):
        for m in re.finditer(r'\\begin\{(\w+)\*?\}', ln):
            if m.group(1) in names:
                stack.append((m.group(1), i))
        for m in re.finditer(r'\\end\{(\w+)\*?\}', ln):
            if stack and m.group(1) == stack[-1][0]:
                spans.append((stack.pop()[1], i))
    return spans


def check(path, min_caption, max_sent, labels, refs):
    body = strip_comments(open(path, encoding='utf-8').read())
    lines = body.split('\n')
    lang = lang_of(path)
    F = []

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    def line_of(off):
        return body[:off].count('\n') + 1

    # ---- E01: caption length -------------------------------------------
    caps = []
    for m in re.finditer(r'\\caption(?:\[[^\]]*\])?\s*\{', body):
        brace = body.index('{', m.end() - 1)
        caps.append((line_of(m.start()), balanced(body, brace)))
    for m in re.finditer(r'\\dgfigure\s*\{', body):
        args, _end = read_args(body, m.end() - 1, 4)
        # the third argument is the long caption; the picture is the fourth
        caps.append((line_of(m.start()), args[2]))
        labels[args[0]].append((path, line_of(m.start())))
    for i, txt in caps:
        n = len(caption_words(txt))
        if n < min_caption:
            add(i, 'E01', 'WARN',
                'caption of %d words (limit %d): that is a label, not an '
                'explanation — say what the figure shows and what the reader '
                'is meant to take from it' % (n, min_caption))

    # ---- labels / refs, for E02 -----------------------------------------
    for i, ln in enumerate(lines, 1):
        for m in re.finditer(r'\\label\{((?:fig|tab|alg|lst):[^}]+)\}', ln):
            labels[m.group(1)].append((path, i))
        for m in re.finditer(r'\\(?:ref|autoref|Cref|cref)\{([^}]+)\}', ln):
            for k in m.group(1).split(','):
                refs.add(k.strip())

    # ---- E03: a section that opens with a subsection ---------------------
    heads = [(i, m.group(1)) for i, ln in enumerate(lines, 1)
             for m in re.finditer(r'\\(chapter|section|subsection)\b', ln)]
    for idx, (i, kind) in enumerate(heads[:-1]):
        if kind not in ('chapter', 'section'):
            continue
        nxt_line, nxt_kind = heads[idx + 1]
        child = 'section' if kind == 'chapter' else 'subsection'
        if nxt_kind != child:
            continue
        between = '\n'.join(lines[i:nxt_line - 1])
        prose = re.sub(r'\\label\{[^}]*\}', '', between)
        prose = re.sub(r'\\[a-zA-Z@]+\*?(\[[^\]]*\])?(\{[^}]*\})?', '', prose)
        if len(prose.split()) < 12:
            what = 'the chapter' if kind == 'chapter' else 'the section'
            add(i, 'E03', 'WARN',
                '%s opens straight into a %s: put 2-3 sentences saying what '
                'it does and in what order' % (what, child))

    # ---- E04: a paragraph of many sentences -----------------------------
    draw = env_spans(body, DRAW + VERB)

    def in_draw(n):
        return any(a <= n <= b for a, b in draw)

    para, start = [], None

    def flush():
        if not para or start is None:
            return
        txt = ' '.join(para)
        txt = re.sub(r'\\(?:cite|ref|eqref|label)\s*\{[^}]*\}', ' ', txt)
        txt = re.sub(r'\$[^$]*\$', ' X ', txt)
        for ab in ABBREV[lang]:
            txt = txt.replace(ab, ab.replace('.', '\u2024'))
        # A full stop, a Greek semicolon (ano teleia) or an exclamation
        # mark ends a sentence.
        sents = [s for s in re.split(r'(?<=[.;!])\s+|·\s+', txt)
                 if len(s.split()) > 2]
        if len(sents) > max_sent:
            add(start, 'E04', 'INFO',
                'paragraph of %d sentences (limit %d): probably more than '
                'one idea' % (len(sents), max_sent))

    for i, ln in enumerate(lines, 1):
        s = ln.strip()
        if not s or s.startswith('\\begin') or s.startswith('\\end') \
                or in_draw(i):
            flush()
            para, start = [], None
            continue
        if re.match(r'\\(chapter|section|subsection|caption|item|dgfigure'
                    r'|bibliography)', s):
            flush()
            para, start = [], None
            continue
        if start is None:
            start = i
        para.append(s)
    flush()
    return F


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--min-caption', type=int, default=15)
    ap.add_argument('--max-sent', type=int, default=8)
    ap.add_argument('--strict', action='store_true')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    # E02 is asked ONCE PER EDITION. Pooling the two meant a Greek \ref
    # excused an orphaned float in the English edition, and the finding
    # was reported against whichever file sorted first — sending the
    # author to the wrong language.
    F = []
    for lang in ('en', 'el'):
        files = [f for f in a.files
                 if os.path.exists(f) and lang_of(f) == lang]
        if not files:
            continue
        labels, refs = collections.defaultdict(list), set()
        for f in files:
            F += check(f, a.min_caption, a.max_sent, labels, refs)
        for k, places in sorted(labels.items()):
            if k not in refs:
                p, i = places[0]
                F.append(Finding(p, i, 'E02', 'WARN',
                                 'no sentence points at \\label{%s}: either '
                                 'write the sentence or delete the float'
                                 % k))
    F.sort(key=lambda x: (x.file, x.line))

    if a.json:
        print(json.dumps([x._asdict() for x in F], ensure_ascii=False,
                         indent=2))
    else:
        for x in F:
            print('%s:%d: [%s/%s] %s' % (x.file, x.line, x.code, x.level,
                                         x.msg))
        nw = sum(1 for x in F if x.level == 'WARN')
        print('\n  total: %d  (WARN %d, INFO %d)' % (len(F), nw, len(F) - nw))
        c = collections.Counter(x.code for x in F)
        if c:
            print('  by code: %s' % dict(c.most_common()))
    return 1 if [x for x in F if x.level == 'WARN' or a.strict] else 0


if __name__ == '__main__':
    sys.exit(main())
