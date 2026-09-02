# -*- coding: utf-8 -*-
"""Κοινές συναρτήσεις ανάλυσης LaTeX για τη ροή παραγωγής DOCX.

Δεν τροποποιεί ΠΟΤΕ τα αρχεία πηγής (chapters/, preamble.tex, bib/).
"""
import os, re

import config

ROOT = config.ROOT


def CHAPTERS():
    """The chapters of the CURRENTLY SELECTED edition.

    A function, not a constant: the pipeline runs once per language and
    a module-level list would freeze whichever language happened to be
    selected when this module was first imported.
    """
    return config.chapters()


def strip_comments(s):
    """Αφαιρεί σχόλια LaTeX. Το '%' στο τέλος γραμμής ενώνει τις γραμμές."""
    out = []
    for line in s.split('\n'):
        res, i, esc = [], 0, False
        cut = None
        while i < len(line):
            c = line[i]
            if esc:
                res.append(c); esc = False
            elif c == '\\':
                res.append(c); esc = True
            elif c == '%':
                cut = i; break
            else:
                res.append(c)
            i += 1
        text = ''.join(res)
        if cut is not None:
            # '%' αμέσως μετά κείμενο => ένωση με την επόμενη γραμμή
            out.append(text + '\x00JOIN\x00')
        else:
            out.append(text)
    s = '\n'.join(out)
    s = re.sub(r'\x00JOIN\x00\n[ \t]*', '', s)
    s = s.replace('\x00JOIN\x00', '')
    return s


def match_brace(s, start):
    """start = δείκτης του '{'. Επιστρέφει (περιεχόμενο, δείκτης_μετά_το_'}')."""
    assert s[start] == '{'
    depth, i, esc = 0, start, False
    while i < len(s):
        c = s[i]
        if esc:
            esc = False
        elif c == '\\':
            esc = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return s[start + 1:i], i + 1
        i += 1
    raise ValueError('αταίριαστο άγκιστρο στη θέση %d' % start)


def find_env(s, env):
    """Αποδίδει (αρχή, τέλος, προαιρετικό_όρισμα, σώμα) για κάθε περιβάλλον."""
    for m in re.finditer(r'\\begin\{%s\}' % re.escape(env), s):
        i = m.end()
        opt = ''
        if i < len(s) and s[i] == '[':
            j = s.index(']', i)
            opt = s[i + 1:j]; i = j + 1
        depth = 1
        k = i
        pat = re.compile(r'\\(begin|end)\{%s\}' % re.escape(env))
        while depth > 0:
            mm = pat.search(s, k)
            if not mm:
                raise ValueError('ακλείστο περιβάλλον %s' % env)
            depth += 1 if mm.group(1) == 'begin' else -1
            k = mm.end()
        end_start = k - len('\\end{%s}' % env)
        yield (m.start(), k, opt, s[i:end_start])


def read_labels():
    """\\newlabel from the .aux files -> {label: (number, page, title)}."""
    labels = {}
    for aux in config.aux_files():
        if not os.path.exists(aux):
            continue
        s = open(aux, encoding='utf-8').read()
        for m in re.finditer(r'\\newlabel\{', s):
            name, i = match_brace(s, m.end() - 1)
            if i < len(s) and s[i] == '{':
                body, _ = match_brace(s, i)
                parts = []
                j = 0
                while j < len(body) and len(parts) < 3:
                    if body[j] == '{':
                        p, j = match_brace(body, j)
                        parts.append(p)
                    else:
                        j += 1
                if parts:
                    labels[name] = tuple(parts + [''] * (3 - len(parts)))
    return labels


def read_bbl_order():
    """Order and numbering from the .bbl -> {key: n}, {n: entry text}."""
    p = config.bbl_file()
    if not os.path.exists(p):
        return {}, {}
    s = open(p, encoding='utf-8').read()
    keys = re.findall(r'\\bibitem\{([^}]*)\}', s)
    num = {k: i + 1 for i, k in enumerate(keys)}
    chunks = re.split(r'\\bibitem\{[^}]*\}', s)[1:]
    text = {}
    for i, ch in enumerate(chunks):
        ch = re.sub(r'\\end\{thebibliography\}.*', '', ch, flags=re.S)
        text[i + 1] = ch.strip()
    return num, text


def cite_keys(arg):
    return [k.strip() for k in arg.split(',') if k.strip()]

def read_args(s, start, n):
    """n consecutive brace groups beginning at `start`. Returns (args, end)."""
    args, i = [], start
    for _ in range(n):
        while i < len(s) and s[i] in ' \n\t':
            i += 1
        if i >= len(s) or s[i] != '{':
            args.append('')
            continue
        a, i = match_brace(s, i)
        args.append(a)
    return args, i


def strip_figures(src, keep_caption=True):
    r"""\dgfigure{label}{short}{long}{body} -> just the long caption.

    The picture becomes a PNG, so its drawing commands are not text in
    either edition; the caption IS text and stays. Every check that
    compares the .docx against the source has to see the same thing the
    converter produced, or it reports differences that are not there.
    """
    out, pos = [], 0
    for m in re.finditer(r'\\dgfigure\s*\{', src):
        if m.start() < pos:
            continue
        args, end = read_args(src, m.end() - 1, 4)
        out.append(src[pos:m.start()])
        out.append('\n\n' + (args[2] if keep_caption else '') + '\n\n')
        pos = end
    out.append(src[pos:])
    return ''.join(out)
