#!/usr/bin/env python3
"""diagram-check — ελέγχει τα σχήματα απέναντι στο πρότυπο.

    python3 diagram-check.py chapters/*.tex figures/*.mmd [--strict] [--json]

Δέχεται .tex (TikZ/pgfplots) και .mmd (Mermaid) και εφαρμόζει σε καθένα
τους κανόνες που το αφορούν. Κωδικός εξόδου 1 σε WARN.

Ο έλεγχος βρίσκει ΥΠΟΨΙΕΣ. Κάθε εύρημα κρίνεται από άνθρωπο.
"""
import re, sys, json, argparse, os, collections, glob

Finding = collections.namedtuple('Finding', 'file line code level msg')

# Η παλέτα Okabe–Ito του preamble.tex
PALETTE = {
    '#0072B2': 'cCFT', '#D55E00': 'cBFT', '#009E73': 'cAux',
    '#E69F00': 'cSand', '#CC79A7': 'cPurple', '#6E6E6E': 'cGrey',
}
# Αποχρώσεις που παράγονται από την παλέτα (γεμίσματα) — θεμιτές.
DERIVED = {
    '#E8F1F8', '#FDECE3', '#FDF1E3', '#FFF8E8', '#EAF3F9',
    '#FFFFFF', '#FFF', '#1A1A1A', '#000000', '#000',
}
SERIES = ('cCFT', 'cBFT', 'cAux', 'cSand', 'cPurple')
LOW_CONTRAST = ('cSand', 'cPurple')      # < 3:1 σε λευκό — θέλουν 2η κωδικοποίηση
MAX_NODES = 7


def norm_hex(h):
    h = h.upper()
    if len(h) == 4:                      # #abc -> #AABBCC
        h = '#' + ''.join(c * 2 for c in h[1:])
    return h


# ------------------------------------------------------------------ .mmd

def check_mmd(path):
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')
    F = []

    # Το είδος καθορίζει ΠΟΙΟΙ κανόνες ισχύουν. Στο sequenceDiagram ο χρόνος
    # είναι σταθερός κατακόρυφος άξονας: μια απόκριση από δεξιά προς τα
    # αριστερά ΔΕΝ είναι ακμή επιστροφής και δεν μπερδεύει τίποτα. Οι Κ2/Κ3/Κ4
    # αφορούν διατάξεις που δρομολογούνται αυτόματα (state, flowchart).
    kind = 'other'
    if re.search(r'^\s*sequenceDiagram\b', src, re.M):
        kind = 'sequence'
    elif re.search(r'^\s*stateDiagram(-v2)?\b', src, re.M):
        kind = 'state'
    elif re.search(r'^\s*(flowchart|graph)\b', src, re.M):
        kind = 'flowchart'
    routed = kind in ('state', 'flowchart')

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    # D06 — λείπει το edgeLabelBackground
    if 'edgeLabelBackground' not in src:
        add(1, 'D06', 'WARN',
            'λείπει το edgeLabelBackground από την επικεφαλίδα — το Mermaid '
            'θα υπολογίσει αρνητική απόχρωση και οι ετικέτες θα βγουν ροζ')

    # D01 — χρώμα εκτός παλέτας
    for i, ln in enumerate(lines, 1):
        for m in re.finditer(r'#[0-9A-Fa-f]{3,6}\b', ln):
            h = norm_hex(m.group(0))
            if len(h) != 7:
                continue
            if h not in PALETTE and h not in DERIVED:
                add(i, 'D01', 'WARN',
                    'χρώμα %s εκτός παλέτας — χρησιμοποίησε ένα από τα έξι '
                    'του preamble.tex' % m.group(0))

    # D05 — ελληνικό αναγνωριστικό κόμβου
    for i, ln in enumerate(lines, 1):
        s = ln.strip()
        if s.startswith(('%%', '#')) or not s:
            continue
        # αριστερό μέλος ακμής
        for m in re.finditer(r'(^|\s)([^\s"\[\]{}()<>|-]+)\s*-{1,2}>{1,2}', ln):
            tok = m.group(2)
            if re.search(r'[\u0370-\u03FF\u1F00-\u1FFF]', tok):
                add(i, 'D05', 'WARN',
                    'ελληνικό αναγνωριστικό «%s» — το Mermaid θέλει ASCII id· '
                    'γράψε  state "%s" as X' % (tok, tok))
                break

    # D03 — αυτο-μετάβαση (μόνο σε διατάξεις που δρομολογούνται αυτόματα)
    for i, ln in enumerate(lines, 1) if routed else []:
        m = re.search(r'(^|\s)(\w+)\s*-{1,2}>{1,2}\s*(\w+)\s*(:|$)', ln)
        if m and m.group(2) == m.group(3):
            add(i, 'D03', 'WARN',
                'αυτο-μετάβαση «%s → %s» (Κ2): σέρνει τις γειτονικές ακμές '
                'έξω από το πλαίσιο· γράψ᾽ την ως σημείωση ή στη λεζάντα'
                % (m.group(2), m.group(3)))

    # D04 / D07 — πλήθος κόμβων και ακμές επιστροφής
    nodes, edges = set(), []
    for ln in lines:
        for m in re.finditer(r'state\s+"[^"]*"\s+as\s+(\w+)', ln):
            nodes.add(m.group(1))
        for m in re.finditer(r'participant\s+(\w+)\s+as\b', ln):
            nodes.add(m.group(1))
        for m in re.finditer(r'^\s*(\w+)\[', ln):
            nodes.add(m.group(1))
        m = re.search(r'(^|\s)(\w+)\s*-{1,2}>{1,2}\s*(\w+)', ln)
        if m and m.group(2) != m.group(3):
            edges.append((m.group(2), m.group(3)))
            nodes.update((m.group(2), m.group(3)))
    nodes.discard('')
    if len(nodes) > MAX_NODES:
        F.append(Finding(path, 1, 'D04', 'WARN',
                         '%d κόμβοι (όριο %d, Κ4): το σχήμα δείχνει πάνω από '
                         'μία ιδέα — σπάσ᾽ το' % (len(nodes), MAX_NODES)))

    # ακμή επιστροφής = προς κόμβο που εμφανίστηκε νωρίτερα.
    # ΔΕΝ ισχύει σε sequenceDiagram: εκεί ο χρόνος είναι ο κατακόρυφος
    # άξονας και μια απόκριση προς τα αριστερά είναι το φυσιολογικό.
    order, back = {}, 0
    for a, b in edges:
        order.setdefault(a, len(order))
        order.setdefault(b, len(order))
    for a, b in edges:
        if order[b] < order[a]:
            back += 1
    if back > 1 and routed:
        F.append(Finding(path, 1, 'D07', 'WARN',
                         '%d ακμές επιστροφής (Κ3): μάζεψέ τις σε μία, '
                         'αλλιώς θα διασταυρωθούν' % back))
    return F


# ------------------------------------------------------------------ .tex

def check_tex(path):
    src = open(path, encoding='utf-8').read()
    src = re.sub(r'(?<!\\)%.*', '', src)
    lines = src.split('\n')
    F = []

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    for i, ln in enumerate(lines, 1):
        # D01 — ωμό hex αντί για όνομα χρώματος
        for m in re.finditer(r'\{HTML\}\{([0-9A-Fa-f]{6})\}', ln):
            h = norm_hex('#' + m.group(1))
            if h not in PALETTE and h not in DERIVED:
                add(i, 'D01', 'WARN', 'χρώμα #%s εκτός παλέτας' % m.group(1))

        # D02 — cGrey ως χρώμα σειράς δεδομένων
        if re.search(r'\\addplot\[[^\]]*\bcGrey\b(?![!])', ln):
            add(i, 'D02', 'WARN',
                'cGrey ως σειρά δεδομένων: το γκρι είναι ΔΟΜΗ (άξονες, '
                'πλέγμα), όχι σειρά — κόβεται στο chroma floor')

        # D09 — δεύτερος άξονας y
        if 'axis y line*=right' in ln.replace(' ', '') or 'ylabel near ticks' in ln:
            add(i, 'D09', 'WARN',
                'δεύτερος άξονας y: απαγορεύεται. Δύο μετρήσεις -> δύο '
                'σχήματα ή κανονικοποίηση σε κοινή βάση')

    # D08 — cSand/cPurple χωρίς δεύτερη κωδικοποίηση μέσα στο ίδιο axis
    for m in re.finditer(r'\\begin\{axis\}(.*?)\\end\{axis\}', src, re.S):
        block = m.group(1)
        i = src[:m.start()].count('\n') + 1
        for name in LOW_CONTRAST:
            for pm in re.finditer(r'\\addplot\[([^\]]*\b%s\b[^\]]*)\]' % name, block):
                opts = pm.group(1)
                has2 = ('mark=' in opts or 'dashed' in opts or 'dotted' in opts
                        or 'pattern' in opts)
                if not has2:
                    add(i, 'D08', 'WARN',
                        '%s χωρίς δεύτερη κωδικοποίηση: η αντίθεσή του σε '
                        'λευκό είναι κάτω από 3:1, άρα χρειάζεται mark= ή '
                        'dashed ή ορατή ετικέτα' % name)

    return F


# ------------------------------------------------------------------ D10

def check_pairing(tex_files, mmd_files, F):
    """Κάθε .mmd πρέπει να έχει αντίστοιχο σχήμα στα κεφάλαια."""
    texsrc = ''
    for t in tex_files:
        try:
            texsrc += open(t, encoding='utf-8').read()
        except OSError:
            pass
    for m in mmd_files:
        stem = os.path.splitext(os.path.basename(m))[0]
        if stem and stem not in texsrc:
            F.append(Finding(m, 1, 'D10', 'INFO',
                             'δεν βρέθηκε «%s» στα κεφάλαια: το σχήμα υπάρχει '
                             'σε Mermaid αλλά ίσως όχι σε TikZ' % stem))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--strict', action='store_true')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    paths = []
    for f in a.files:
        paths.extend(glob.glob(f) if any(c in f for c in '*?[') else [f])

    tex = [p for p in paths if p.endswith('.tex') and os.path.exists(p)]
    mmd = [p for p in paths if p.endswith('.mmd') and os.path.exists(p)]

    F = []
    for p in tex:
        F += check_tex(p)
    for p in mmd:
        F += check_mmd(p)
    check_pairing(tex, mmd, F)
    F.sort(key=lambda x: (x.file, x.line))

    if a.json:
        print(json.dumps([x._asdict() for x in F], ensure_ascii=False, indent=2))
    else:
        for x in F:
            print('%s:%d: [%s/%s] %s' % (x.file, x.line, x.code, x.level, x.msg))
        nw = sum(1 for x in F if x.level == 'WARN')
        print('\n  αρχεία: %d tex · %d mmd' % (len(tex), len(mmd)))
        print('  σύνολο: %d  (WARN %d · INFO %d)' % (len(F), nw, len(F) - nw))
        c = collections.Counter(x.code for x in F)
        if c:
            print('  ανά κωδικό: %s' % dict(c.most_common()))
    return 1 if [x for x in F if x.level == 'WARN' or a.strict] else 0


if __name__ == '__main__':
    sys.exit(main())
