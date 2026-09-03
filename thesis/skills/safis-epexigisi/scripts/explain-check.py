#!/usr/bin/env python3
"""explain-check — μετρά αν ένα κεφάλαιο έχει τη ΔΟΜΗ που κάνει την εξήγηση
δυνατή. Δεν κρίνει αν εξηγεί· αυτό είναι ανθρώπινη δουλειά.

Τέσσερις έλεγχοι, όλοι μετρήσιμοι:

  E01  λεζάντα κάτω από --min-caption λέξεις (ετικέτα, όχι εξήγηση)
  E02  float που δεν παραπέμπεται από το κείμενο
  E03  ενότητα που ξεκινά κατευθείαν με υποενότητα
  E04  παράγραφος πάνω από --max-sent προτάσεις

    python3 explain-check.py chapters/*.tex [--min-caption 15] [--max-sent 8] [--json]

Κωδικός εξόδου 1 αν βρεθεί WARN.
"""
import re, sys, json, argparse, os, collections

Finding = collections.namedtuple('Finding', 'file line code level msg')

VERB = ('verbatim', 'lstlisting', 'minted', 'Verbatim')
DRAW = ('tikzpicture', 'axis', 'groupplot', 'tabular', 'tabularx', 'array',
        'align', 'equation', 'aligned', 'matrix', 'pmatrix', 'bmatrix')


def strip_comments(t):
    return re.sub(r'(?<!\\)%.*', '', t)


def balanced(s, start):
    """Επιστρέφει το περιεχόμενο του { } που αρχίζει στο s[start] == '{'."""
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


def caption_words(text):
    """Λέξεις λεζάντας, αφού αφαιρεθούν εντολές, μαθηματικά και παραπομπές."""
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
    F = []

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    # ---- E01: μήκος λεζάντας -------------------------------------------
    for m in re.finditer(r'\\caption(?:\[[^\]]*\])?\s*\{', body):
        brace = body.index('{', m.end() - 1)
        txt = balanced(body, brace)
        n = len(caption_words(txt))
        i = body[:m.start()].count('\n') + 1
        if n < min_caption:
            add(i, 'E01', 'WARN',
                'λεζάντα %d λέξεων (όριο %d): είναι ετικέτα, όχι εξήγηση — '
                'πες τι δείχνει το σχήμα και τι πρέπει να καταλάβει ο αναγνώστης'
                % (n, min_caption))

    # ---- labels / refs για το E02 ---------------------------------------
    for i, ln in enumerate(lines, 1):
        for m in re.finditer(r'\\label\{((?:fig|tab|alg|lst):[^}]+)\}', ln):
            labels[m.group(1)].append((path, i))
        for m in re.finditer(r'\\(?:ref|autoref|Cref|cref)\{([^}]+)\}', ln):
            for k in m.group(1).split(','):
                refs.add(k.strip())

    # ---- E03: ενότητα που ανοίγει με υποενότητα -------------------------
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
            what = 'το κεφάλαιο' if kind == 'chapter' else 'η ενότητα'
            add(i, 'E03', 'WARN',
                '%s ανοίγει κατευθείαν με %s· βάλε 2-3 προτάσεις που λένε '
                'τι κάνει και με ποια σειρά' % (what, child))

    # ---- E04: παράγραφος με πολλές προτάσεις ----------------------------
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
        # οι ελληνικές συντομογραφίες δεν τερματίζουν περίοδο
        for ab in ('κ.ά.', 'κ.λπ.', 'π.χ.', 'βλ.', 'σσ.', 'σ.', 'ό.π.', 'κ.ο.κ.'):
            txt = txt.replace(ab, ab.replace('.', '\u2024'))
        # ελληνική τελεία/άνω τελεία/ερωτηματικό ως τέλος περιόδου
        sents = [s for s in re.split(r'(?<=[.;!])\s+|·\s+', txt) if len(s.split()) > 2]
        if len(sents) > max_sent:
            add(start, 'E04', 'INFO',
                'παράγραφος %d προτάσεων (όριο %d): πιθανώς πάνω από μία ιδέα'
                % (len(sents), max_sent))

    for i, ln in enumerate(lines, 1):
        s = ln.strip()
        if not s or s.startswith('\\begin') or s.startswith('\\end') or in_draw(i):
            flush()
            para, start = [], None
            continue
        if re.match(r'\\(chapter|section|subsection|caption|item|bibliography)', s):
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

    labels, refs = collections.defaultdict(list), set()
    F = []
    for f in a.files:
        if os.path.exists(f):
            F += check(f, a.min_caption, a.max_sent, labels, refs)

    for k, places in sorted(labels.items()):
        if k not in refs:
            p, i = places[0]
            F.append(Finding(p, i, 'E02', 'WARN',
                             'το \\label{%s} δεν το δείχνει καμία πρόταση: '
                             'ή γράψε την πρόταση ή σβήσε το float' % k))
    F.sort(key=lambda x: (x.file, x.line))

    if a.json:
        print(json.dumps([x._asdict() for x in F], ensure_ascii=False, indent=2))
    else:
        for x in F:
            print('%s:%d: [%s/%s] %s' % (x.file, x.line, x.code, x.level, x.msg))
        nw = sum(1 for x in F if x.level == 'WARN')
        print('\n  σύνολο: %d  (WARN %d · INFO %d)' % (len(F), nw, len(F) - nw))
        c = collections.Counter(x.code for x in F)
        if c:
            print('  ανά κωδικό: %s' % dict(c.most_common()))
    return 1 if [x for x in F if x.level == 'WARN' or a.strict] else 0


if __name__ == '__main__':
    sys.exit(main())
