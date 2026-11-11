#!/usr/bin/env python3
"""latex-check — μηχανικός έλεγχος LaTeX κατά τις οδηγίες του Spinellis.

Πηγή κανόνων: github.com/dspinellis/latex-advice (CC BY-SA 4.0), προσαρμοσμένο
για ελληνικό κείμενο σε XeLaTeX.

    python3 latex-check.py chapters/*.tex [--strict] [--json] [--width N]

Έξοδος: «αρχείο:γραμμή: [ΚΩΔΙΚΟΣ] μήνυμα». Κωδικός εξόδου 1 αν βρεθεί
σφάλμα (WARN). Οι κωδικοί INFO δεν αποτυγχάνουν, εκτός με --strict.

Ο έλεγχος βρίσκει ΥΠΟΨΙΕΣ. Κάθε εύρημα κρίνεται από άνθρωπο.
"""
import re, sys, json, argparse, os, collections

# ---------------------------------------------------------------- υποδομή

VERB = ('verbatim', 'lstlisting', 'minted', 'Verbatim')
DRAWING = ('tikzpicture', 'axis', 'groupplot', 'pgfplots', 'tabular', 'tabularx',
           'array', 'align', 'aligned', 'matrix', 'cases', 'pmatrix', 'bmatrix',
           'equation', 'eqnarray', 'split', 'gather', 'multline')

Finding = collections.namedtuple('Finding', 'file line code level msg')


def masked(text):
    """Επιστρέφει το κείμενο με τα σχόλια και τα verbatim σβησμένα (ίδιο μήκος),
    ώστε οι θέσεις γραμμών να μένουν σωστές."""
    out = []
    in_verb = None
    for ln in text.split('\n'):
        if in_verb:
            out.append('' if not re.search(r'\\end\{%s\*?\}' % in_verb, ln) else ln)
            if re.search(r'\\end\{%s\*?\}' % in_verb, ln):
                in_verb = None
            continue
        m = re.search(r'\\begin\{(%s)\*?\}' % '|'.join(VERB), ln)
        if m:
            in_verb = m.group(1)
            out.append('')
            continue
        # σβήσε σχόλιο, κρατώντας το \%
        out.append(re.sub(r'(?<!\\)%.*', '', ln))
    return '\n'.join(out)


def env_spans(text, names):
    """[(start_line, end_line)] για τα environments που δίνονται, 1-indexed."""
    spans, stack = [], []
    for i, ln in enumerate(text.split('\n'), 1):
        for m in re.finditer(r'\\begin\{(\w+)\*?\}', ln):
            if m.group(1) in names:
                stack.append((m.group(1), i))
        for m in re.finditer(r'\\end\{(\w+)\*?\}', ln):
            if stack and m.group(1) == stack[-1][0]:
                spans.append((stack.pop()[1], i))
    return spans


def in_span(line, spans):
    return any(a <= line <= b for a, b in spans)


# ---------------------------------------------------------------- έλεγχοι

def check_file(path, width, all_labels, all_refs):
    raw = open(path, encoding='utf-8').read()
    txt = masked(raw)
    lines = txt.split('\n')
    F = []

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    draw = env_spans(txt, DRAWING)
    floats = env_spans(txt, ('figure', 'table'))

    for i, ln in enumerate(lines, 1):
        if not ln.strip():
            continue

        # L01 — \\ για αλλαγή γραμμής μέσα σε πρόζα
        if re.search(r'\\\\', ln) and not in_span(i, draw):
            add(i, 'L01', 'WARN',
                r'\\ έξω από πίνακα/σχήμα: η αλλαγή γραμμής είναι χειροκίνητη μορφοποίηση')

        # L02 — παλιές εντολές γραμματοσειράς
        for old, new in (('bf', 'textbf'), ('it', 'textit'), ('tt', 'texttt'),
                         ('sc', 'textsc'), ('em', 'emph'), ('rm', 'textrm')):
            if re.search(r'\{\s*\\%s(?![a-zA-Z])' % old, ln):
                add(i, 'L02', 'WARN', r'{\%s ...} παλιάς μορφής· χρησιμοποίησε \%s{...}' % (old, new))

        # L03 — $...$ αντί για \(...\)  [συνέπεια, όχι σφάλμα]
        if not in_span(i, draw):
            stripped = re.sub(r'\\\$', '', ln)
            if stripped.count('$') >= 2 and '$$' not in stripped:
                add(i, 'L03', 'INFO', r'ένθετα μαθηματικά με $...$· ο Spinellis προτείνει \(...\)')

        # L04 — αποσιωπητικά
        if re.search(r'(?<!\.)\.\.\.(?!\.)', ln):
            add(i, 'L04', 'WARN', r'"..." αντί για \dots')

        # L05 — ίσια διπλά εισαγωγικά
        if re.search(r'(?<!\\)"', ln):
            add(i, 'L05', 'WARN', 'ίσιο εισαγωγικό "· χρησιμοποίησε \\enquote{} ή «»')

        # L06 — κενό αντί για ~ πριν από \cite/\ref  [διορθώνεται με --fix]
        for m in re.finditer(r'(\S)[ ](\\(?:cite|ref|eqref)\b)', ln):
            if m.group(1) not in '~([{,;:':
                add(i, 'L06', 'INFO',
                    'κενό αντί για ~ πριν από %s (μπορεί να σπάσει η γραμμή)' % m.group(2))

        # L07 — αριθμός σχήματος/πίνακα με το χέρι.
        # ΕΞΑΙΡΕΣΗ: αν στην ίδια γραμμή υπάρχει \cite, ο αριθμός πιθανότατα
        # δείχνει σε πίνακα/σχήμα ΤΗΣ ΠΗΓΗΣ («ο Πίνακας 1 του [20]»), που
        # είναι θεμιτό. Δηλώνεται ως INFO για ανθρώπινη κρίση.
        for m in re.finditer(r'\b(Σχήμα|Σχ\.|Πίνακα[ς]?|Πίν\.|Ενότητα|Κεφάλαιο|'
                             r'Figure|Table|Section|Chapter)~?\s*(\d+)', ln):
            # παράθυρο ±1 γραμμή: το LaTeX αναδιπλώνει, οπότε το \cite μπορεί
            # να έχει πέσει στην επόμενη ή στην προηγούμενη γραμμή
            window = '\n'.join(lines[max(0, i - 2):i + 1])
            cites_here = r'\cite' in window
            add(i, 'L07', 'INFO' if cites_here else 'WARN',
                'γραμμένο με το χέρι: «%s %s»· χρησιμοποίησε \\ref{}%s'
                % (m.group(1), m.group(2),
                   ' — ή αγνόησέ το αν δείχνει σε πίνακα της πηγής' if cites_here else ''))

        # L08 — αριθμός παραπομπής με το χέρι.
        # Εξαιρούνται τα μαθηματικά: το $[0,1]$ είναι διάστημα, όχι παραπομπή.
        nomath = re.sub(r'\$[^$]*\$', lambda m: ' ' * len(m.group(0)), ln)
        for m in re.finditer(r'(?<![\w\]])\[(\d+(?:\s*[,–-]\s*\d+)*)\](?!\{)', nomath):
            add(i, 'L08', 'WARN',
                'αριθμός παραπομπής «[%s]» με το χέρι· χρησιμοποίησε \\cite{}' % m.group(1))

        # L10 — \hline / κατακόρυφες γραμμές σε πίνακα
        if r'\hline' in ln:
            add(i, 'L10', 'WARN', r'\hline· με booktabs: \toprule \midrule \bottomrule')
        m = re.search(r'\\begin\{tabular[x*]?\}(?:\[[^\]]*\])?\{([^}]*)\}', ln)
        if m and '|' in m.group(1):
            add(i, 'L10', 'WARN', 'κατακόρυφη γραμμή «|» στον ορισμό στηλών· βγάλ᾽ την')

        # L11 — center μέσα σε float
        if r'\begin{center}' in ln and in_span(i, floats):
            add(i, 'L11', 'WARN', r'\begin{center} μέσα σε float· χρησιμοποίησε \centering')

        # L15 — σημάδι εκκρεμότητας μέσα στο ΤΥΠΩΜΕΝΟ κείμενο.
        # ΠΡΟΣΟΧΗ: το latex-advice ΣΥΝΙΣΤΑ τα TODO/XXX σε ΣΧΟΛΙΑ. Εδώ
        # ελέγχεται το κείμενο με τα σχόλια ήδη σβησμένα από τη masked(),
        # άρα ό,τι βρεθεί θα ΤΥΠΩΘΕΙ στο PDF. Αυτό μόνο είναι σφάλμα.
        m = re.search(r'\b(TODO|FIXME|XXX|ΤΟΔΟ)\b', ln)
        if m:
            add(i, 'L15', 'WARN',
                'εκκρεμότητα «%s» ΕΚΤΟΣ σχολίου — θα τυπωθεί στο PDF '
                '(μέσα σε %% σχόλιο είναι θεμιτή)' % m.group(1))

        # L17 — διπλή λέξη
        for m in re.finditer(r'\b(\w{2,})\s+\1\b', ln, re.UNICODE):
            if m.group(1).lower() not in (
                    'που', 'να', 'θα', 'μια', 'ένα', 'λίγο', 'σιγά', 'δυο',
                    'δύο', 'ένας', 'πότε', 'άλλο', 'κάθε'):
                add(i, 'L17', 'WARN', 'διπλή λέξη «%s»' % m.group(1))

        # L18 — υποσημείωση πριν από το σημείο στίξης
        if re.search(r'\\footnote\{[^}]*\}\s*[.,;·]', ln):
            add(i, 'L18', 'INFO', r'\footnote πριν από το σημείο στίξης· μπαίνει μετά')

        # L19 — υπογράμμιση
        if r'\underline' in ln:
            add(i, 'L19', 'WARN', r'\underline· χρησιμοποίησε \emph ή \textbf')

        # L21 — αριθμός κολλητά σε μονάδα χωρίς αδιάσπαστο κενό
        for m in re.finditer(r'(?<![\w~,.])(\d+(?:[.,]\d+)?)\s(ms|s|ns|GB|MB|KB|GiB|MiB|'
                             r'GHz|MHz|kB|B|W|€)\b', ln):
            add(i, 'L21', 'INFO',
                'αριθμός και μονάδα «%s %s» χωρίς ~ ή \\,' % (m.group(1), m.group(2)))

        # L23 — τελεία συντομογραφίας χωρίς \
        for m in re.finditer(r'\b(κ\.ά|κ\.λπ|π\.χ|βλ|σ|σσ|εκδ|επιμ|πρβλ)\.\s+(?=[α-ωίϊΐόάέύϋΰήώ])', ln):
            add(i, 'L23', 'INFO',
                'συντομογραφία «%s.» χωρίς \\ μετά: το TeX θα βάλει κενό τέλους περιόδου' % m.group(1))

        # L24 — υπερβολικό [H]
        if re.search(r'\\begin\{(figure|table)\}\[H\]', ln):
            add(i, 'L24', 'INFO', 'τοποθέτηση [H]: εμποδίζει το LaTeX να στοιβάξει σωστά')

        # L16 — μακριά γραμμή (μόνο με --width)
        if width and len(ln) > width and not in_span(i, draw):
            add(i, 'L16', 'INFO', 'γραμμή %d χαρακτήρων (όριο %d)' % (len(ln), width))

    # ---- έλεγχοι σε επίπεδο float ----
    for a, b in floats:
        block = '\n'.join(lines[a - 1:b])
        cap = re.search(r'\\caption', block)
        lab = re.search(r'\\label', block)
        if not lab:
            add(a, 'L12', 'WARN', 'float χωρίς \\label')
        if cap and lab and lab.start() < cap.start():
            add(a, 'L09', 'WARN',
                r'\label πριν από το \caption: το LaTeX θα αριθμήσει λάθος')
        if not cap:
            add(a, 'L26', 'WARN', 'float χωρίς \\caption')

    # μάζεψε labels/refs για διασταυρούμενο έλεγχο
    for i, ln in enumerate(lines, 1):
        for m in re.finditer(r'\\label\{([^}]+)\}', ln):
            all_labels[m.group(1)].append((path, i))
        for m in re.finditer(r'\\(?:ref|eqref|autoref|Cref|cref)\{([^}]+)\}', ln):
            for k in m.group(1).split(','):
                all_refs[k.strip()].append((path, i))
    return F


# Περιοχές που ΔΕΝ αγγίζει ποτέ το --fix: ενσωματωμένο verbatim και
# οτιδήποτε μοιάζει με διεύθυνση ή διαδρομή. Το «...b» μέσα σε URL θα γινόταν
# \dotsb — υπαρκτή εντολή του amsmath, άρα σιωπηλή αλλοίωση χωρίς σφάλμα.
PROTECTED = re.compile(
    r'\\(?:url|href|path|nolinkurl)\s*\{[^}]*\}'      # \url{...}
    r'|\\verb\*?(.)(?:(?!\1).)*\1'                    # \verb|...|
    r'|\\texttt\s*\{[^}]*\}'                          # \texttt{...}
    r'|(?<!\\)%.*$'                                   # σχόλιο ως το τέλος
)


def autofix_line(ln, counters):
    """Εφαρμόζει τις δύο ασφαλείς αντικαταστάσεις ΜΟΝΟ στα μη προστατευμένα
    τμήματα της γραμμής."""
    out, last = [], 0
    for m in PROTECTED.finditer(ln):
        out.append(_apply(ln[last:m.start()], counters))
        out.append(m.group(0))            # αυτούσιο, χωρίς καμία αλλαγή
        last = m.end()
    out.append(_apply(ln[last:], counters))
    return ''.join(out)


def _apply(seg, counters):
    def sub6(m):
        if m.group(1) in '~([{,;:':
            return m.group(0)
        counters[0] += 1
        return m.group(1) + '~' + m.group(2)

    def sub4(m):
        counters[1] += 1
        return r'\dots'

    seg = re.sub(r'(\S)[ ](\\(?:cite|ref|eqref)\b)', sub6, seg)
    seg = re.sub(r'(?<!\.)\.\.\.(?!\.)', sub4, seg)
    return seg


def autofix(path):
    """Διορθώνει ΜΟΝΟ τα δύο μηχανικά ασφαλή: L06 (~ πριν από παραπομπή) και
    L04 (... -> \\dots).

    ΔΕΝ αγγίζει: σχόλια (ούτε στο τέλος γραμμής), verbatim/lstlisting/minted,
    σχεδιαστικά environments, \\url, \\href, \\path, \\verb, \\texttt.
    Επιστρέφει (πλήθος_L06, πλήθος_L04)."""
    raw = open(path, encoding='utf-8').read()
    lines = raw.split('\n')
    mask = masked(raw).split('\n')
    draw = env_spans(masked(raw), DRAWING)
    counters = [0, 0]
    for i, ln in enumerate(lines):
        if not mask[i].strip():
            continue                      # ολόκληρη γραμμή σχόλιο ή verbatim
        if in_span(i + 1, draw):
            continue
        lines[i] = autofix_line(ln, counters)
    if counters[0] or counters[1]:
        open(path, 'w', encoding='utf-8').write('\n'.join(lines))
    return counters[0], counters[1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--strict', action='store_true', help='και τα INFO αποτυγχάνουν')
    ap.add_argument('--json', action='store_true')
    ap.add_argument('--fix', action='store_true',
                    help='διορθώνει επί τόπου ΜΟΝΟ τα L06 και L04 (μηχανικά ασφαλή)')
    ap.add_argument('--width', type=int, default=0,
                    help='όριο μήκους γραμμής (0 = χωρίς έλεγχο)')
    a = ap.parse_args()

    if a.fix:
        t6 = t4 = 0
        for f in a.files:
            if os.path.exists(f):
                n6, n4 = autofix(f)
                if n6 or n4:
                    print('%s: %d× ~ πριν από παραπομπή, %d× \\dots' % (f, n6, n4))
                t6 += n6
                t4 += n4
        print('\n  διορθώθηκαν: %d (L06) + %d (L04)' % (t6, t4))
        print('  ΜΕΤΑΓΛΩΤΤΙΣΕ ΚΑΙ ΔΕΣ ΤΟ diff πριν το commit.')
        return 0

    labels, refs = collections.defaultdict(list), collections.defaultdict(list)
    findings = []
    for f in a.files:
        if not os.path.exists(f):
            print('παράλειψη (δεν υπάρχει): %s' % f, file=sys.stderr)
            continue
        findings += check_file(f, a.width, labels, refs)

    # L13 — label που δεν το δείχνει κανείς.
    # Ένα float που δεν το δείχνει καμία πρόταση είναι πραγματικό πρόβλημα
    # (ο Spinellis το λέει ρητά: «refer to all floats in the text»).
    # Ένα label ενότητας που δεν χρησιμοποιήθηκε ΑΚΟΜΗ είναι φυσιολογικό σε
    # κείμενο υπό συγγραφή, οπότε είναι INFO.
    for k, places in sorted(labels.items()):
        if k not in refs:
            p, i = places[0]
            is_float = k.split(':')[0] in ('fig', 'tab', 'eq', 'lst', 'alg')
            findings.append(Finding(
                p, i, 'L13', 'WARN' if is_float else 'INFO',
                'το \\label{%s} δεν παραπέμπεται πουθενά%s'
                % (k, ' — κάθε σχήμα/πίνακας πρέπει να δείχνεται από το κείμενο'
                   if is_float else '')))
    # L14 — ref σε ανύπαρκτο label
    for k, places in sorted(refs.items()):
        if k not in labels:
            p, i = places[0]
            findings.append(Finding(p, i, 'L14', 'WARN',
                                    'παραπομπή σε ανύπαρκτο \\label{%s}' % k))
    # L27 — διπλό label
    for k, places in sorted(labels.items()):
        if len(places) > 1:
            p, i = places[1]
            findings.append(Finding(p, i, 'L27', 'WARN', 'διπλό \\label{%s}' % k))

    findings.sort(key=lambda f: (f.file, f.line))

    if a.json:
        print(json.dumps([f._asdict() for f in findings], ensure_ascii=False, indent=2))
    else:
        counts = collections.Counter(f.code for f in findings)
        for f in findings:
            print('%s:%d: [%s/%s] %s' % (f.file, f.line, f.code, f.level, f.msg))
        n_warn = sum(1 for f in findings if f.level == 'WARN')
        n_info = len(findings) - n_warn
        print('\n  σύνολο: %d  (WARN %d · INFO %d)' % (len(findings), n_warn, n_info))
        if counts:
            print('  ανά κωδικό: %s' % dict(counts.most_common()))

    bad = [f for f in findings if f.level == 'WARN' or a.strict]
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
