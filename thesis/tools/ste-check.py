#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ste-check — the mechanical checks of Simplified Technical English.

The English edition is the master, so this is where the prose is judged
first. tools/ate-check.py is the mirror of this for the Greek.

It checks only what a machine can check without arguing about taste.
Style is a human judgement; these are counts.

  S1  a sentence longer than the cap (20 words strict, 25 flavoured)
  S2  more than one semicolon in a sentence, outside an enumeration
  S3  a passive with the agent hidden ("it has been shown that")
  S4  a light verb carrying a nominalisation ("perform an analysis")
  S6  three or more -ing participles in one sentence
  S7  an enumeration of four or more items inside a sentence
  L1  the same idea named by two different words in one document

    python3 tools/ste-check.py content/en/*.tex [--mode strict|flavored]

Exit code 1 if a violation is found.
"""
import re, sys, json, statistics, collections

CAPS = {'strict': 20, 'flavored': 25}

# A light (empty) verb plus a nominalised noun: STE asks for the verb.
NOMEN = r'\w+(?:tion|sion|ment|ance|ence|ysis|ation|ing)'
LIGHT = [rf'\b(?:perform|carry out|conduct|undertake|make|do|effect)\s+'
         rf'(?:an?|the)\s+{NOMEN}\b',
         rf'\bprovide\s+(?:an?|the)\s+{NOMEN}\b',
         rf'\btake\s+(?:into\s+)?(?:account|consideration)\b',
         rf'\bgive\s+(?:an?|the)\s+{NOMEN}\b']

HIDDEN_PASSIVE = [r'\bit (?:has been|is|was|can be) (?:shown|proved|proven|'
                  r'argued|claimed|believed|assumed|considered|noted|'
                  r'reported|observed|said)\b',
                  r'\bit is (?:well[- ]known|widely (?:known|accepted))\b',
                  r'\bhas been (?:demonstrated|established)\b']

# Pairs that name one idea twice. Keep this list short and specific: a
# generic synonym list produces noise, not findings.
#
# NOT IN THIS LIST, AND WHY. Three pairs look like synonyms in general
# English and are not synonyms in this subject, so flagging them would
# train the author to ignore the check:
#   node / replica       a node is a machine; a replica is a copy of the
#                        state machine running on one. §2.3 counts them
#                        separately, and the whole of §2.3.1 is about not
#                        confusing the two counts.
#   crash-fault / fail-stop   §2.2 states the difference explicitly:
#                        fail-stop adds that the stop is detected
#                        reliably, which is what makes Schneider's t+1
#                        bound hold.
#   safety / liveness    opposites, not alternatives.
TERM_PAIRS = [('throughput', 'output rate'),
              ('latency', 'response time'),
              ('deployment', 'installation'),
              ('quorum', 'majority set')]

# NOT CHECKED, AND WHY: noun clusters ("the leader election timeout
# value"). Deciding which words in a row are nouns needs a tagger; a
# heuristic on word endings reported "something bad never" and "every
# later chapter" as clusters. A check that produces noise gets ignored,
# and then the checks that do work get ignored with it.

STOP = set('''the a an and or but of in on at to for with by from as is are
was were be been being that this these those it its which who whom whose
not no if then than so such can may might must shall should will would
we our you your they their he she his her one two three four five'''.split())


def _keeplines(repl=' '):
    def f(m):
        return repl + '\n' * m.group(0).count('\n')
    return f


def strip_tex(t):
    t = re.sub(r'(?<!\\)%.*', '', t)
    for env in ('dgdata', 'dgbarchart', 'dgflow', 'tikzpicture', 'tabular',
                'verbatim', 'lstlisting', 'equation', 'align'):
        t = re.sub(r'\\begin\{%s\*?\}.*?\\end\{%s\*?\}' % (env, env),
                   _keeplines(), t, flags=re.S)
    t = re.sub(r'\$[^$]*\$', ' X ', t)
    t = re.sub(r'\\(?:cite|ref|eqref|label|index)\s*(\[[^\]]*\])?\{[^}]*\}',
               ' ', t)
    t = re.sub(r'\\dgfigure\s*\{[^}]*\}', ' ', t)
    t = re.sub(r'\\(?:enquote|emph|textbf|textit|texttt|en|enup|gr)\{', ' ', t)
    # An environment's optional argument is machinery, not prose:
    # "\begin{description}[leftmargin=...]" was being read as a sentence.
    t = re.sub(r'\\(?:begin|end)\{[a-zA-Z*]+\}(\[[^\]]*\])?', ' ', t)
    t = re.sub(r'\\[a-zA-Z@]+\*?(\[[^\]]*\])?', ' ', t)
    t = t.replace('{', ' ').replace('}', ' ').replace('~', ' ')
    return t


ABBREV = ('et al.', 'e.g.', 'i.e.', 'cf.', 'vs.', 'Fig.', 'Sec.', 'Ch.',
          'Eq.', 'pp.', 'No.', 'approx.')


def sentences_with_lines(t):
    """[(sentence, line number)] — the line matters for the report."""
    guarded = t
    for ab in ABBREV:
        guarded = guarded.replace(ab, ab.replace('.', '\u2024'))
    out, pos = [], 0
    for m in re.finditer(r'(?<=[.!?])\s+', guarded):
        s = guarded[pos:m.start()].strip()
        if len(s.split()) > 2:
            out.append((s.replace('\u2024', '.'),
                        guarded[:pos].count('\n') + 1))
        pos = m.end()
    tail = guarded[pos:].strip()
    if len(tail.split()) > 2:
        out.append((tail.replace('\u2024', '.'),
                    guarded[:pos].count('\n') + 1))
    return out


def words(s):
    return re.findall(r"[A-Za-z][A-Za-z'’-]*", s)


def enumeration_context(s):
    return bool(re.search(r'\b(?:first|second|third|following|these|three|'
                          r'four|five)\b', s, re.I)) or ':' in s



def check(path, mode='flavored'):
    raw = open(path, encoding='utf-8').read()
    t = strip_tex(raw) if path.endswith('.tex') else raw
    t = re.sub(r'[ \t]+', ' ', t)
    ss = sentences_with_lines(t)
    cap = CAPS[mode]
    v, lens = [], []
    for s, ln in ss:
        w = words(s)
        lens.append(len(w))
        if len(w) > cap:
            v.append((ln, 'S1', 'sentence of %d words (cap %d): "%s..."'
                      % (len(w), cap, s[:70])))
        n_semi = s.count(';')
        if n_semi >= 2 and not enumeration_context(s):
            v.append((ln, 'S2', '%d semicolons in one sentence with no '
                      'enumeration' % n_semi))
        elif n_semi >= 3:
            v.append((ln, 'S7', 'an enumeration of %d items inside a '
                      'sentence — use a vertical list' % (n_semi + 1)))
        for pat in HIDDEN_PASSIVE:
            for m in re.finditer(pat, s, re.I):
                v.append((ln, 'S3', 'passive with a hidden agent: "%s" — '
                          'name the source' % m.group(0)))
        for pat in LIGHT:
            for m in re.finditer(pat, s, re.I):
                v.append((ln, 'S4', 'light verb carrying a noun: "%s"'
                          % m.group(0)))
        ings = [x for x in re.findall(r'\b\w+ing\b', s)
                if x.lower() not in ('during', 'nothing', 'something',
                                     'string', 'ring', 'according',
                                     'being', 'having')]
        if len(ings) >= 3:
            v.append((ln, 'S6', '%d -ing forms in one sentence: %s'
                      % (len(ings), ings[:4])))

    low = t.lower()
    for a, b in TERM_PAIRS:
        ca = len(re.findall(r'(?<![A-Za-z])' + re.escape(a), low))
        cb = len(re.findall(r'(?<![A-Za-z])' + re.escape(b), low))
        if ca and cb:
            v.append((0, 'L1', 'two words for one idea: "%s" x%d and "%s" '
                      'x%d — pick one' % (a, ca, b, cb)))

    stats = dict(sentences=len(ss),
                 mean=round(statistics.mean(lens), 1) if lens else 0,
                 median=statistics.median(lens) if lens else 0,
                 maxlen=max(lens) if lens else 0,
                 over_cap=sum(1 for x in lens if x > cap),
                 over_cap_pct=round(100 * sum(1 for x in lens if x > cap)
                                    / len(lens), 1) if lens else 0)
    return stats, sorted(v)


def main():
    argv = sys.argv[1:]
    mode, files, asjson, skip = 'flavored', [], False, False
    for i, a in enumerate(argv):
        if skip:
            skip = False
            continue
        if a == '--mode':
            mode, skip = argv[i + 1], True
            continue
        if a.startswith('--mode='):
            mode = a.split('=', 1)[1]
            continue
        if a == '--json':
            asjson = True
            continue
        if a.startswith('--'):
            continue
        files.append(a)
    if mode not in CAPS:
        sys.exit('unknown mode: %s (strict or flavored)' % mode)
    if not files:
        sys.exit('usage: ste-check.py FILE... [--mode strict|flavored]')
    total = 0
    for p in files:
        st, vs = check(p, mode)
        total += len(vs)
        if asjson:
            print(json.dumps({'file': p, 'stats': st,
                              'violations': [{'line': l, 'code': c,
                                              'msg': m} for l, c, m in vs]},
                             ensure_ascii=False, indent=2))
            continue
        print('%s: %d sentences, mean %.1f words, longest %d, over cap %d '
              '(%.1f%%)' % (p, st['sentences'], st['mean'], st['maxlen'],
                            st['over_cap'], st['over_cap_pct']))
        for ln, code, msg in vs:
            print('  %s:%d: [%s] %s' % (p, ln, code, msg))
    if not asjson:
        print('\n  total violations: %d  (mode %s)' % (total, mode))
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
