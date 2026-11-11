#!/usr/bin/env python3
"""ate-check — μηχανικός έλεγχος ΑΤΕ (Απλά Τεχνικά Ελληνικά).

Ελέγχει τους 6 μηχανικούς ελέγχους της §6 του SKILL.md. Δεν κρίνει ύφος.
Δέχεται .tex, .md ή σκέτο κείμενο.

    python3 ate-check.py chapter1.tex [--mode strict|flavored] [--json]

Έξοδος: μία γραμμή ανά παράβαση, «αρχείο:γραμμή: [ΚΩΔΙΚΟΣ] μήνυμα».
Κωδικός εξόδου 1 αν βρεθεί παράβαση. Κατάλληλο για pre-commit / CI.
"""
import re, sys, json, statistics, collections

ART = set('ο η το του της των τον την τους τις τα οι ένα ένας μια μία έναν'.split())
GEN = {'του', 'της', 'των'}
# ρήμα-φορέας = άδειο ρήμα + ονοματοποιημένο ουσιαστικό (-ση/-ση/-μός/-ία/-ιση)
NOMEN = r'\w+(?:ση|σης|σεις|σεων|μός|μού|μοί|ία|ίας|ιση|ισης)'
LIGHT = [rf'κάν\w+ (?:τ[ηοα]\w*\s+)?{NOMEN}', rf'πραγματοποι\w+ (?:τ\w+\s+)?{NOMEN}',
         rf'προβαίν\w+ σε (?:\w+\s+)?{NOMEN}', r'λαμβάνει χώρα',
         rf'έχει ως αποτέλεσμα', rf'τίθεται σε εφαρμογή', rf'διενεργ\w+ (?:τ\w+\s+)?{NOMEN}']
HIDDEN_PASSIVE = [r'έχει δειχθεί', r'έχει αποδειχθεί', r'θεωρείται ότι', r'είναι γνωστό ότι',
                  r'αναφέρεται ότι', r'πιστεύεται ότι', r'υποστηρίζεται ότι']
TERM_PAIRS = [('επίδοσ', 'απόδοσ'), ('αποτίμησ', 'αξιολόγησ'), ('ανάπτυξ', 'εγκατάστασ')]
CAPS = {'strict': (24, 24), 'flavored': (30, 30)}

VERBISH = {'είναι','ήταν','έχει','έχουν','είχε','γίνεται','αποτελεί','υπάρχει','υπάρχουν',
           'δίνει','δείχνει','απαιτεί','ισχύει','σημαίνει','περιλαμβάνει','θεωρείται'}

def strip_tex(t):
    t = re.sub(r'(?<!\\)%.*', '', t)
    # τίτλοι ενοτήτων: δική τους περίοδος, να μη συγχωνεύονται με το επόμενο κείμενο
    t = re.sub(r'\\(chapter|section|subsection|paragraph)\*?\{([^}]*)\}', r' \2. ', t)
    # κάθε \item είναι δικό του όριο πρότασης
    t = re.sub(r'\\item\s*(\[[^\]]*\])?', ' . ', t)
    t = re.sub(r'\\begin\{(tikzpicture|tabular|verbatim|lstlisting)\}.*?\\end\{\1\}', ' ', t, flags=re.S)
    t = re.sub(r'\$[^$]*\$', ' Χ ', t)
    t = re.sub(r'\\cite\{[^}]*\}|\\label\{[^}]*\}', '', t)
    t = re.sub(r'\\ref\{[^}]*\}', 'Χ', t)
    t = re.sub(r'\\[a-zA-Z@]+\*?(\[[^\]]*\])?', ' ', t)
    return t.replace('{', ' ').replace('}', ' ').replace('~', ' ').replace('\\', ' ')

def strip_md(t):
    t = re.sub(r'```.*?```', ' ', t, flags=re.S)
    t = re.sub(r'^\s*\|.*$', ' ', t, flags=re.M)
    t = re.sub(r'^\s*(#{1,6}|>).*$', ' ', t, flags=re.M)
    return re.sub(r'[*`_]', '', t)

WORD = re.compile(r'[Α-Ωα-ωΆ-Ώά-ώϊϋΐΰA-Za-z0-9]+')
def words(s): return WORD.findall(s)

def sentences_with_lines(text):
    """Επιστρέφει (πρόταση, αριθμός_γραμμής). Το «;» είναι ερωτηματικό, μετρά ως τέλος."""
    out, line, buf, start = [], 1, [], 1
    for ch in text:
        if ch == '\n':
            line += 1; buf.append(' '); continue
        buf.append(ch)
        if ch in '.!;':
            s = ''.join(buf).strip()
            if len(words(s)) > 2: out.append((s, start))
            buf, start = [], line
    if buf:
        s = ''.join(buf).strip()
        if len(words(s)) > 2: out.append((s, start))
    return out

def enumeration_context(s):
    """Άνω τελείες μετά από «:» θεωρούνται διαχωριστικά καταλόγου (εξαίρεση Δ2)."""
    return ':' in s and s.index(':') < (s.index('·') if '·' in s else len(s))

def check(path, mode='flavored'):
    raw = open(path, encoding='utf-8').read()
    t = strip_tex(raw) if path.endswith('.tex') else (strip_md(raw) if path.endswith('.md') else raw)
    t = re.sub(r'[ \t]+', ' ', t)
    ss = sentences_with_lines(t)
    cap = CAPS[mode][1]
    v = []
    lens = []
    for s, ln in ss:
        w = words(s); lens.append(len(w))
        if len(w) > cap:
            v.append((ln, 'Δ1', f'περίοδος {len(w)} λέξεων (όριο {cap}): «{s[:70]}…»'))
        n_ano = s.count('·')
        if n_ano >= 2 and not enumeration_context(s):
            v.append((ln, 'Δ2', f'{n_ano} άνω τελείες σε μία περίοδο χωρίς απαρίθμηση'))
        elif n_ano >= 2 and enumeration_context(s) and n_ano >= 3:
            v.append((ln, 'Δ7', f'απαρίθμηση {n_ano+1} στοιχείων μέσα σε περίοδο — χρησιμοποιήστε κάθετη λίστα'))
        # Δ5 αλυσίδα γενικών: 3+ γενικές σε παράθυρο 8 λέξεων, χωρίς ενδιάμεσο ρήμα/κόμμα
        lw = [x.lower() for x in w]
        for i in range(len(lw) - 7):
            win = lw[i:i+8]
            if sum(1 for x in win if x in GEN) >= 3 and not (set(win) & VERBISH):
                frag = ' '.join(w[i:i+8])
                if ',' not in frag and '·' not in frag:
                    v.append((ln, 'Δ5', f'αλυσίδα ≥3 γενικών: «{frag}»')); break
        # Δ9 μετοχές
        met = re.findall(r'\b\w+[οώ]ντας\b', s)
        if len(met) >= 2:
            v.append((ln, 'Δ9', f'{len(met)} μετοχές -οντας/-ώντας σε μία περίοδο: {met}'))
        for pat in LIGHT:
            for m in re.finditer(pat, s):
                v.append((ln, 'Δ4', f'ρήμα-φορέας: «{m.group(0)}»'))
        for pat in HIDDEN_PASSIVE:
            for m in re.finditer(pat, s):
                v.append((ln, 'Δ3', f'παθητική με κρυμμένο δράστη: «{m.group(0)}» — κατονομάστε την πηγή'))
    # Λ1 εναλλαγή όρων (σε επίπεδο εγγράφου)
    low = t.lower()
    for a, b in TERM_PAIRS:
        ca = len(re.findall(r'(?<![Α-Ωα-ωΆ-Ώά-ώ])'+a, low))
        cb = len(re.findall(r'(?<![Α-Ωα-ωΆ-Ώά-ώ])'+b, low))
        if ca and cb:
            v.append((0, 'Λ1', f'εναλλαγή όρων: «{a}*»×{ca} και «{b}*»×{cb} — επιλέξτε έναν'))
    stats = dict(sentences=len(ss), mean=round(statistics.mean(lens), 1) if lens else 0,
                 median=statistics.median(lens) if lens else 0, maxlen=max(lens) if lens else 0,
                 over_cap=sum(1 for x in lens if x > cap),
                 over_cap_pct=round(100*sum(1 for x in lens if x > cap)/len(lens), 1) if lens else 0,
                 ano_teleia=t.count('·'), articles_pct=round(
                     100*sum(1 for x in words(t) if x.lower() in ART)/max(1, len(words(t))), 1))
    return stats, sorted(v)

if __name__ == '__main__':
    argv = sys.argv[1:]
    mode = 'flavored'
    args, skip = [], False
    for i, a in enumerate(argv):
        if skip:
            skip = False
            continue
        if a == '--mode':
            if i + 1 < len(argv):
                mode = argv[i + 1]
                skip = True          # η τιμή του --mode ΔΕΝ είναι αρχείο
            continue
        if a.startswith('--mode='):
            mode = a.split('=', 1)[1]
            continue
        if a.startswith('--'):
            continue
        args.append(a)
    if mode not in ('strict', 'flavored'):
        sys.exit(f"Άγνωστος τρόπος: {mode}. Χρησιμοποίησε strict ή flavored.")
    if not args:
        sys.exit('Χρήση: ate-check.py ΑΡΧΕΙΟ... [--mode strict|flavored] [--json]')
    asjson = '--json' in sys.argv
    total = 0
    for p in args:
        st, vs = check(p, mode)
        total += len(vs)
        if asjson:
            print(json.dumps({'file': p, 'stats': st, 'violations':
                              [{'line': l, 'rule': r, 'msg': m} for l, r, m in vs]}, ensure_ascii=False, indent=2))
            continue
        print(f'\n== {p}  [τρόπος: {mode}]')
        print(f'   {st["sentences"]} περίοδοι · μέσο {st["mean"]} λέξεις · διάμεσος {st["median"]} · max {st["maxlen"]}')
        print(f'   πάνω από το όριο: {st["over_cap"]} ({st["over_cap_pct"]} %) · άνω τελείες: {st["ano_teleia"]} · άρθρα: {st["articles_pct"]} %')
        by = collections.Counter(r for _, r, _ in vs)
        print(f'   παραβάσεις: {len(vs)}  {dict(by)}')
        for l, r, m in vs:
            print(f'   {p}:{l}: [{r}] {m}')
    sys.exit(1 if total else 0)
