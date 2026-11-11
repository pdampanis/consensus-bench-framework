#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Keeps the Greek edition honest about how far behind the English it is.

English is the master. Greek is a translation of it. The danger is not
that someone forgets to translate a new paragraph — that is obvious. The
danger is that someone edits an English paragraph that was already
translated, and the Greek quietly keeps saying the old thing. Nobody
notices until an examiner reads both.

So every paragraph carries an id, and the Greek copy records the hash of
the exact English text it was made from:

    content/en/ch1.tex     %%blk intro-01
    content/el/ch1.tex     %%blk intro-01 from=8f2a1c4d9e07

Change the English and the hash no longer matches. The block is STALE,
`make check` fails, and the report names the file, the id and the first
line of English that moved.

    python3 tools/sync.py            report, exit 1 if anything is wrong
    python3 tools/sync.py --stamp    re-stamp every Greek block (see below)
    python3 tools/sync.py --stamp --id intro-01
    python3 tools/sync.py --new      list ids present in EN but not EL

--stamp writes "this Greek text now matches this English text". Only run
it on a block you have ACTUALLY just translated. Running it on everything
does not synchronise anything; it only destroys the evidence that the two
had drifted apart.
"""
import argparse
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EN = os.path.join(ROOT, 'content', 'en')
EL = os.path.join(ROOT, 'content', 'el')

# The from= value is matched loosely on purpose. A freshly written Greek
# block carries "from=PLACEHOLDER"; a strict 12-hex pattern would fail to
# match the line at all, the block would read as MISSING rather than
# UNSTAMPED, and the report would be wrong in the confusing direction.
BLOCK = re.compile(r'^%%blk[ \t]+([A-Za-z0-9_.:-]+)'
                   r'(?:[ \t]+from=(\S+))?[ \t]*$', re.M)
HASH = re.compile(r'^[0-9a-f]{12}$')


def digest(text):
    """Hash the meaning, not the line wrapping.

    Whitespace is normalised first, so re-flowing a paragraph to a
    different line width does NOT mark the translation stale. Changing a
    word does. That is the distinction the whole file exists to make.
    """
    norm = re.sub(r'\s+', ' ', text).strip()
    return hashlib.sha256(norm.encode('utf-8')).hexdigest()[:12]


def parse(path):
    """[(id, recorded_hash, body, header_line_number)] in file order."""
    if not os.path.exists(path):
        return []
    src = open(path, encoding='utf-8').read()
    marks = list(BLOCK.finditer(src))
    out = []
    for i, m in enumerate(marks):
        start = m.end()
        end = marks[i + 1].start() if i + 1 < len(marks) else len(src)
        line = src.count('\n', 0, m.start()) + 1
        out.append((m.group(1), m.group(2), src[start:end], line))
    return out


def prologue(path):
    """Prose that sits BEFORE the first block, and is therefore tracked by
    nothing.

    The report used to say "every block translated and stamped", which
    was literally true and said nothing about how much of the file was
    inside a block. A paragraph written above the first marker was 100%
    untracked and 0% reported.
    """
    if not os.path.exists(path):
        return 0
    src = open(path, encoding='utf-8').read()
    m = BLOCK.search(src)
    head = src[:m.start()] if m else src
    head = re.sub(r'(?<!\\)%.*', '', head)          # comments do not count
    return len(head.split())


def duplicates(blocks):
    seen, dup = set(), []
    for bid, _h, _b, line in blocks:
        if bid in seen:
            dup.append((bid, line))
        seen.add(bid)
    return dup


def chapters():
    """Every chapter file on EITHER side, in name order.

    Listing only the English side meant a Greek chapter with no English
    counterpart passed every check: the loop never looked at it. It is
    the same failure as an untranslated English chapter, in the other
    direction, and it has to be as visible.
    """
    names = set()
    for d in (EN, EL):
        if os.path.isdir(d):
            names |= set(f for f in os.listdir(d) if f.endswith('.tex'))
    return sorted(names)


def report():
    stale, missing, orphan, order, unmarked = [], [], [], [], []
    total = 0
    for name in chapters():
        en = parse(os.path.join(EN, name))
        el = parse(os.path.join(EL, name))
        total += len(en)
        elmap = {b[0]: b for b in el}
        enmap = {b[0]: b for b in en}

        if not en:
            unmarked.append('%s: no %%%%blk markers on the English side'
                            % name)
        for path, lang in ((os.path.join(EN, name), 'en'),
                           (os.path.join(EL, name), 'el')):
            n = prologue(path)
            if n > 12:
                unmarked.append('%s (%s): %d words sit above the first '
                                '%%%%blk marker and are tracked by nothing'
                                % (name, lang, n))
        for blocks, lang in ((en, 'en'), (el, 'el')):
            for bid, line in duplicates(blocks):
                unmarked.append('%s (%s) line %d: a second block with the '
                                'id "%s". Ids must be unique — the second '
                                'one silently replaces the first, and '
                                '--stamp then writes its hash over the '
                                'real one' % (name, lang, line, bid))

        for bid, _h, body, line in en:
            if bid not in elmap:
                missing.append('%s:%d  %s — not translated yet: "%s"'
                               % (name, line, bid, first_words(body)))
                continue
            _b, recorded, elbody, elline = elmap[bid]
            want = digest(body)
            if recorded is None or not HASH.match(recorded):
                stale.append('%s:%d  %s — never stamped (from=%s). Translate '
                             'it, then: python3 tools/sync.py --stamp --id %s'
                             % (name, elline, bid, recorded or 'absent', bid))
            elif recorded != want:
                stale.append('%s:%d  %s — English changed since translation '
                             '(stamp %s, now %s)\n        EN: "%s"\n        EL: "%s"'
                             % (name, elline, bid, recorded, want,
                                first_words(body), first_words(elbody)))

        for bid, _h, body, line in el:
            if bid not in enmap:
                orphan.append('%s:%d  %s — Greek block with no English '
                              'counterpart: "%s"'
                              % (name, line, bid, first_words(body)))

        en_ids = [b[0] for b in en if b[0] in elmap]
        el_ids = [b[0] for b in el if b[0] in enmap]
        if en_ids != el_ids:
            order.append('%s: the two editions put the same blocks in a '
                         'different order' % name)

    print('EN blocks: %d in %d file(s)' % (total, len(chapters())))
    bad = 0
    for title, items in (('NOT TRANSLATED', missing),
                         ('STALE — English moved on', stale),
                         ('ORPHANED — no English counterpart', orphan),
                         ('OUT OF ORDER', order),
                         ('NO MARKERS', unmarked)):
        if items:
            bad += len(items)
            print('\n%s (%d):' % (title, len(items)))
            for it in items:
                print('  ' + it)
    if not bad:
        print('Greek is level with English: every block translated and '
              'stamped against the current English text.')
    return 1 if bad else 0


def first_words(body, n=9):
    words = re.sub(r'\s+', ' ', re.sub(r'%.*', '', body)).strip().split()
    s = ' '.join(words[:n])
    return (s + '…') if len(words) > n else s


def stamp(only=None):
    """Write the current English hash into the Greek blocks."""
    changed = 0
    for name in chapters():
        path = os.path.join(EL, name)
        if not os.path.exists(path):
            continue
        en = {b[0]: digest(b[2]) for b in parse(os.path.join(EN, name))}
        src = open(path, encoding='utf-8').read()

        def rep(m):
            nonlocal changed
            bid = m.group(1)
            if only and bid != only:
                return m.group(0)
            if bid not in en:
                return m.group(0)
            new = '%%%%blk %s from=%s' % (bid, en[bid])
            if new != m.group(0).rstrip():
                changed += 1
            return new

        out = BLOCK.sub(rep, src)
        if out != src:
            open(path, 'w', encoding='utf-8').write(out)
    print('stamped %d block(s)' % changed)
    return 0


def new_ids():
    for name in chapters():
        el = {b[0] for b in parse(os.path.join(EL, name))}
        for bid, _h, body, line in parse(os.path.join(EN, name)):
            if bid not in el:
                print('%s:%d  %s  %s' % (name, line, bid, first_words(body)))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--stamp', action='store_true')
    ap.add_argument('--id')
    ap.add_argument('--new', action='store_true')
    a = ap.parse_args()
    if a.stamp:
        return stamp(a.id)
    if a.new:
        return new_ids()
    return report()


if __name__ == '__main__':
    sys.exit(main())
