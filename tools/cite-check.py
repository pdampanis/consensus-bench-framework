#!/usr/bin/env python3
"""cite-check — έλεγχος παραπομπών και εγγραφών BibTeX.

Πηγή κανόνων: www2.dmst.aueb.gr/dds/res/cite/ (Diomidis Spinellis, CC BY-SA
3.0 GR) και github.com/dspinellis/latex-advice (CC BY-SA 4.0), συν τη
σύμβαση `verification` της ίδιας της εργασίας.

    python3 cite-check.py chapters/*.tex --bib bib/references.bib [--strict] [--json]

Έξοδος: «αρχείο:γραμμή: [ΚΩΔΙΚΟΣ/ΕΠΙΠΕΔΟ] μήνυμα». Κωδικός εξόδου 1 σε WARN.
"""
import re, sys, json, argparse, os, collections

Finding = collections.namedtuple('Finding', 'file line code level msg')

# Υποχρεωτικά πεδία ανά τύπο — από το cite/cont.htm
REQUIRED = {
    'article':       [('author',), ('title',), ('journal',), ('year',)],
    'book':          [('author', 'editor'), ('title',), ('publisher',), ('year',)],
    'inbook':        [('author', 'editor'), ('title',), ('publisher',), ('year',),
                      ('chapter', 'pages')],
    'incollection':  [('author',), ('title',), ('booktitle',), ('publisher',),
                      ('year',), ('pages',)],
    'inproceedings': [('author',), ('title',), ('booktitle',), ('year',)],
    'conference':    [('author',), ('title',), ('booktitle',), ('year',)],
    'mastersthesis': [('author',), ('title',), ('school',), ('year',)],
    'phdthesis':     [('author',), ('title',), ('school',), ('year',)],
    'techreport':    [('author',), ('title',), ('institution',), ('year',)],
    'unpublished':   [('author',), ('title',), ('note',)],
    'manual':        [('title',)],
    'proceedings':   [('title',), ('year',)],
    'booklet':       [('title',)],
    'misc':          [],
}

# Κωδικοί επαλήθευσης. Τα GREY και PREPRINT ΕΙΝΑΙ έγκυρα σήματα της σύμβασης
# (δηλώνουν ότι το ΕΡΓΟ δεν έχει κριθεί) και μετρούν ως τεκμηριωμένο
# καθεστώς — αλλιώς ο έλεγχος κατηγορεί ως ελλιπείς τις γκρίζες εγγραφές.
VERIF_CODES = ('VERIFIED-PDF', 'VERIFIED-DOI', 'VERIFIED-IDX', 'VERIFIED-WEB',
               'UNVERIFIED', 'GREY', 'PREPRINT')


def strip_comments(t):
    return re.sub(r'(?<!\\)%.*', '', t)


# ------------------------------------------------------------ ανάγνωση .bib

def parse_bib(path):
    """Επιστρέφει {key: {'type':…, 'line':…, 'fields':{…}, 'raw':…}}.
    Απλός parser: αρκεί για καλοσχηματισμένα αρχεία, και τα κακοσχηματισμένα
    θα τα πιάσει το ίδιο το BibTeX."""
    src = open(path, encoding='utf-8').read()
    entries, order = {}, []
    # γραμμή κάθε εγγραφής
    linestarts = {}
    for m in re.finditer(r'^[ \t]*@(\w+)\s*\{\s*([^,\s]+)\s*,', src, re.M):
        linestarts[m.group(2)] = src[:m.start()].count('\n') + 1

    for chunk in re.split(r'\n(?=[ \t]*@)', src):
        m = re.match(r'\s*@(\w+)\s*\{\s*([^,\s]+)\s*,(.*)', chunk, re.S)
        if not m:
            continue
        typ, key, body = m.group(1).lower(), m.group(2).strip(), m.group(3)
        fields = {}
        # πεδίο = {…} ή "…" ή γυμνός αριθμός/μακροεντολή
        for fm in re.finditer(
                r'(\w+)\s*=\s*(\{(?:[^{}]|\{[^{}]*\})*\}|"[^"]*"|[^,\n}]+)', body):
            name = fm.group(1).lower()
            val = fm.group(2).strip()
            fields[name] = val
        entries[key] = {'type': typ, 'line': linestarts.get(key, 1),
                        'fields': fields, 'raw': chunk}
        order.append(key)
    return entries, order


def unwrap(v):
    v = v.strip()
    while len(v) > 1 and ((v[0] == '{' and v[-1] == '}') or (v[0] == '"' and v[-1] == '"')):
        v = v[1:-1].strip()
    return v


# ------------------------------------------------------------ έλεγχοι .bib

def check_bib(path, entries, order, cited):
    F = []

    def add(line, code, level, msg):
        F.append(Finding(path, line, code, level, msg))

    seen = collections.Counter(order)
    for k, n in seen.items():
        if n > 1:
            add(entries[k]['line'], 'C03', 'WARN', 'διπλό κλειδί «%s» (%d φορές)' % (k, n))

    for key, e in entries.items():
        ln, typ, f = e['line'], e['type'], e['fields']

        # C04 — υποχρεωτικά πεδία
        for group in REQUIRED.get(typ, []):
            if not any(g in f for g in group):
                add(ln, 'C04', 'WARN',
                    '%s{%s}: λείπει %s' % (typ, key, ' ή '.join(group)))

        # C05 — DOI με πρόθεμα resolver
        if 'doi' in f:
            d = unwrap(f['doi'])
            if d.startswith('http') or 'doi.org' in d:
                add(ln, 'C05', 'WARN',
                    '%s: το DOI περιέχει URL· κράτα μόνο το 10.xxxx/…' % key)

        # C06 — URL ενώ υπάρχει DOI
        if 'doi' in f and 'url' in f:
            add(ln, 'C06', 'INFO',
                '%s: υπάρχει και DOI και URL· το DOI αρκεί και δεν σαπίζει' % key)

        # C10 — εύρος σελίδων με απλή παύλα
        if 'pages' in f:
            p = unwrap(f['pages'])
            if re.match(r'^\s*\d+\s*-\s*\d+\s*$', p):
                add(ln, 'C10', 'WARN', '%s: pages «%s» με απλή παύλα· θέλει --' % (key, p))

        # C08 — μήνας ολογράφως
        if 'month' in f:
            mo = unwrap(f['month'])
            if re.match(r'^[A-Za-zΑ-Ωα-ω]{4,}$', mo):
                add(ln, 'C08', 'INFO',
                    '%s: month = %s· χρησιμοποίησε συμβολικό (jan, feb, …)' % (key, mo))

        # C07 — ακρωνύμιο/κύριο όνομα σε τίτλο χωρίς άγκιστρα
        if 'title' in f:
            t = unwrap(f['title'])
            bare = re.sub(r'\{[^}]*\}', '', t)
            for w in re.findall(r'(?<![\w{])([A-Z]{2,}|[A-Z][a-z]*[A-Z]\w*)\b', bare):
                add(ln, 'C07', 'INFO',
                    '%s: το «%s» στον τίτλο δεν προστατεύεται με άγκιστρα — '
                    'το IEEEtran μπορεί να το πεζογραφήσει' % (key, w))
                break

        # C12 — δική σου σύμβαση: πεδίο verification
        if 'verification' not in f:
            add(ln, 'C12', 'WARN', '%s: λείπει το πεδίο verification' % key)
        else:
            v = unwrap(f['verification'])
            if not any(c in v for c in VERIF_CODES):
                add(ln, 'C12', 'WARN',
                    '%s: το verification δεν περιέχει κανέναν γνωστό κωδικό (%s)'
                    % (key, ', '.join(VERIF_CODES)))
            # C13 — UNVERIFIED και παραπέμπεται ΧΩΡΙΣ σήμανση \unver.
            # Η παραπομπή σε ανεπαλήθευτη πηγή δεν είναι σφάλμα· το
            # σφάλμα είναι να μην το ξέρει ο αναγνώστης. Αρκεί λοιπόν να
            # φέρει κάθε σημείο χρήσης τη σήμανση \unver (κανόνας R15).
            if 'UNVERIFIED' in v and key in cited:
                unmarked = [pl for pl in cited[key] if not pl[2]]
                if unmarked:
                    add(ln, 'C13', 'WARN',
                        '%s: σημειωμένο UNVERIFIED και παραπέμπεται σε %d '
                        'σημείο(α) ΧΩΡΙΣ τη σήμανση \\unver — επαλήθευσέ το '
                        'ή σήμανέ το στο σημείο χρήσης' % (key, len(unmarked)))

        # C02 — εγγραφή που δεν παραπέμπεται
        if key not in cited:
            add(ln, 'C02', 'INFO', '%s: υπάρχει στο .bib αλλά δεν παραπέμπεται' % key)

    return F


# ------------------------------------------------------------ έλεγχοι .tex

# «παραπομπή ως ουσιαστικό» — ο κανόνας του latex-advice, στα ελληνικά.
# ΠΡΟΣΟΧΗ: το διάστημα πρέπει να δέχεται ΚΑΙ το «~», γιατί ο κανόνας L06 του
# latex-check (και το --fix του) το επιβάλλει· και το \cite δέχεται
# προαιρετικό όρισμα \cite[σ.~8]{…}. Χωρίς αυτά τα δύο, ο έλεγχος σιωπά
# ακριβώς πάνω στο σωστά μορφοποιημένο κείμενο.
_C = r'\\cite(?:\[[^\]]*\])?\{'
_S = r'[\s~]+'
NOUN_PATTERNS = [
    (r'\b[Σσ]ύμφωνα με τ[οη]' + _S + _C, '«σύμφωνα με το \\cite{…}»'),
    (r'(?:^|[.·;!]\s+|\s)[ΣσΟο]τ[οη]' + _S + _C, '«στο \\cite{…}»'),
    (r'\b[Αα]πό τ[οη]' + _S + _C, '«από το \\cite{…}»'),
    (r'\b[Ττ]ου' + _S + _C, '«του \\cite{…}»'),
    (r'\bAccording to' + _S + _C, '«According to \\cite{…}»'),
    (r'(?:^|[.·;!]\s+|\s)[Ττ][οη]' + _S + _C, 'το \\cite{…} ως υποκείμενο'),
]


MARK_WINDOW = 400   # χαρακτήρες εκατέρωθεν — περίπου μία παράγραφος/λεζάντα


def check_tex(path, cited, bib, F):
    raw = open(path, encoding='utf-8').read()
    body = strip_comments(raw)
    lines = body.split('\n')

    def add(i, code, level, msg):
        F.append(Finding(path, i, code, level, msg))

    # ---- C14 σε επίπεδο αρχείου, με παράθυρο γειτονιάς ----
    # Ο κανόνας (§1.6 / R9) ζητά σήμανση «στο σημείο όπου χρησιμοποιείται» η
    # πηγή — όχι κολλητά στο \cite. Ο συγγραφέας σημαίνει τον ΟΡΟ
    # («τεχνική τεκμηρίωση\grey»), όχι την παραπομπή. Ψάχνουμε λοιπόν
    # \grey ή \prep μέσα σε ±MARK_WINDOW χαρακτήρες.
    for m in re.finditer(r'\\cite(?:\[[^\]]*\])?\{([^}]+)\}', body):
        near = body[max(0, m.start() - MARK_WINDOW): m.end() + MARK_WINDOW]
        marked = bool(re.search(r'\\(?:grey|prep)\b', near))
        if marked:
            continue
        line_no = body[:m.start()].count('\n') + 1
        for k in m.group(1).split(','):
            k = k.strip()
            e = bib.get(k)
            if not e:
                continue
            v = unwrap(e['fields'].get('verification', ''))
            # ΣΥΜΒΑΣΗ (Κεφ. 1 §1.6): η σήμανση αφορά το ΕΡΓΟ, όχι το ΑΝΤΙΤΥΠΟ.
            # Σήμα είναι ΜΟΝΟ το κεφαλαίο αυτοτελές PREPRINT/GREY στο
            # verification. Το «Αντίτυπο: preprint arXiv:…» στο note σημαίνει
            # ότι το αντίτυπο είναι preprint ενώ το έργο ΕΧΕΙ κριθεί.
            is_grey = bool(re.search(r'\bGREY\b', v))
            is_prep = bool(re.search(r'\bPREPRINT\b', v))
            if is_grey or is_prep:
                add(line_no, 'C14', 'WARN',
                    '%s: %s πηγή, και δεν βρέθηκε %s μέσα σε %d χαρακτήρες γύρω '
                    'από την παραπομπή' % (k, 'γκρίζα' if is_grey else 'preprint',
                                           r'\grey' if is_grey else r'\prep',
                                           MARK_WINDOW))

    for i, ln in enumerate(lines, 1):
        for m in re.finditer(r'\\cite(?:\[[^\]]*\])?\{([^}]+)\}', ln):
            # Third element: does this use site carry the \unver marker?
            # C13 asks not "is it cited" but "is the reader told".
            marked = ln[m.end():m.end() + 8].startswith('\\unver')
            for k in m.group(1).split(','):
                cited[k.strip()].append((path, i, marked))

        # C11 — η παραπομπή χρησιμοποιείται ως ουσιαστικό
        for pat, label in NOUN_PATTERNS:
            if re.search(pat, ln):
                add(i, 'C11', 'INFO',
                    '%s — η παραπομπή δεν είναι ουσιαστικό· ονόμασε τους συγγραφείς '
                    '(«Οι Χ κ.ά.~\\cite{…} δείχνουν…»)' % label)
                break

    # C01 — παραπομπή σε ανύπαρκτο κλειδί
    for k, places in cited.items():
        if k not in bib:
            p, i = places[0]
            add_f = Finding(p, i, 'C01', 'WARN', 'παραπομπή σε ανύπαρκτο κλειδί «%s»' % k)
            F.append(add_f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--bib', required=True)
    ap.add_argument('--strict', action='store_true')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    bib, order = parse_bib(a.bib)
    cited = collections.defaultdict(list)
    F = []
    for f in a.files:
        if os.path.exists(f):
            check_tex(f, cited, bib, F)
    # C01 μία φορά ανά ανύπαρκτο κλειδί, όχι μία ανά αρχείο
    seen01 = set()
    F[:] = [x for x in F if not (x.code == 'C01' and (x.msg in seen01 or seen01.add(x.msg)))]
    F += check_bib(a.bib, bib, order, cited)
    F.sort(key=lambda x: (x.file, x.line))

    if a.json:
        print(json.dumps([x._asdict() for x in F], ensure_ascii=False, indent=2))
    else:
        for x in F:
            print('%s:%d: [%s/%s] %s' % (x.file, x.line, x.code, x.level, x.msg))
        nw = sum(1 for x in F if x.level == 'WARN')
        print('\n  εγγραφές: %d · παραπεμπόμενες: %d' % (len(bib), len(cited)))
        print('  σύνολο ευρημάτων: %d  (WARN %d · INFO %d)' % (len(F), nw, len(F) - nw))
        c = collections.Counter(x.code for x in F)
        if c:
            print('  ανά κωδικό: %s' % dict(c.most_common()))

    return 1 if [x for x in F if x.level == 'WARN' or a.strict] else 0


if __name__ == '__main__':
    sys.exit(main())
