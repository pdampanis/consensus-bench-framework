# -*- coding: utf-8 -*-
"""Everything in the .docx pipeline that depends on which language.

The pipeline runs twice, once per edition, and the two runs must not see
each other's files. So every PATH and every string the pipeline itself
prints lives here.

What does NOT live here, and should not: the handful of places where a
check has to know something about a language rather than about a file —
the regular expression for "a long word" in verify.py, the words that
introduce the keywords line in frontmatter.py. Those are properties of
Greek and English, not of this build, and they are commented where they
are.

Select the language once, at the top of a script:

    import config
    config.select('el')

after which config.LANG, config.CHAPTERS, config.OUT and the rest point
at that edition.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, 'docx')
BUILD = os.path.join(HERE, 'build')

LANG = 'en'


def select(lang):
    """Point the module at one edition. Call before anything else."""
    global LANG
    if lang not in ('en', 'el'):
        raise SystemExit('language must be en or el, not %r' % lang)
    LANG = lang
    return LANG


# --- paths ------------------------------------------------------------
def content_dir():
    return os.path.join(ROOT, 'content', LANG)


def chapters():
    d = content_dir()
    return [os.path.join(d, f) for f in sorted(os.listdir(d))
            if f.endswith('.tex')]


def frontmatter_dir():
    return os.path.join(ROOT, 'frontmatter', LANG)


def labels_file():
    return os.path.join(ROOT, 'figures', 'labels-%s.tex' % LANG)


def main_tex():
    return os.path.join(ROOT, 'main-%s.tex' % LANG)


def aux_files():
    """Where LaTeX left the numbering for this edition."""
    out = [os.path.join(ROOT, 'thesis-%s.aux' % LANG)]
    for sub in ('content', 'frontmatter'):
        d = os.path.join(ROOT, sub, LANG)
        if os.path.isdir(d):
            out += [os.path.join(d, f) for f in sorted(os.listdir(d))
                    if f.endswith('.aux')]
    return out


def bbl_file():
    return os.path.join(ROOT, 'thesis-%s.bbl' % LANG)


def pdf_file():
    return os.path.join(ROOT, 'thesis-%s.pdf' % LANG)


def figures_dir():
    """Rendered figure images. Per language: the labels inside differ."""
    return os.path.join(BUILD, 'figures-%s' % LANG)


def reference_docx():
    return os.path.join(BUILD, 'reference-%s.docx' % LANG)


def body_tex():
    return os.path.join(BUILD, 'body-%s.tex' % LANG)


def body_docx():
    return os.path.join(BUILD, 'body-%s.docx' % LANG)


def out_docx():
    return os.path.join(BUILD, 'thesis-%s.docx' % LANG)


def manifest():
    return os.path.join(BUILD, 'manifest-%s.json' % LANG)


# --- the words the pipeline itself has to print -----------------------
# Everything the .docx contains that is NOT in a source file: the words
# around the numbers, the front-matter headings, the placeholder text in
# an unfilled field.
STRINGS = {
    'en': {
        'chapter': 'CHAPTER',
        'figure': 'Figure',
        'table': 'Table',
        'bibliography': 'REFERENCES',
        'toc': 'TABLE OF CONTENTS',
        'list_images': 'LIST OF IMAGES',
        'list_figures': 'LIST OF FIGURES',
        'list_tables': 'LIST OF TABLES',
        'abbreviations': 'ABBREVIATIONS',
        'declaration': "AUTHOR'S DECLARATION",
        'acknowledgements': 'ACKNOWLEDGEMENTS',
        'abstract': 'ABSTRACT',
        'thesis_kind': 'DIPLOMA THESIS',
        'supervisor': 'Supervisor:',
        'committee': 'Examination committee:',
        'am': 'Registration number ',
        'blank_leaf': '(This page is intentionally blank)',
        'toc_hint': 'Select all (Ctrl+A) and press F9 to fill in the '
                    'table of contents.',
        'lof_hint': 'Press F9 to fill in the list of figures.',
        'lot_hint': 'Press F9 to fill in the list of tables.',
    },
    'el': {
        'chapter': 'ΚΕΦΑΛΑΙΟ',
        'figure': 'Σχήμα',
        'table': 'Πίνακας',
        'bibliography': 'ΒΙΒΛΙΟΓΡΑΦΙΑ',
        'toc': 'ΠΕΡΙΕΧΟΜΕΝΑ',
        'list_images': 'ΚΑΤΑΛΟΓΟΣ ΕΙΚΟΝΩΝ',
        'list_figures': 'ΚΑΤΑΛΟΓΟΣ ΣΧΗΜΑΤΩΝ',
        'list_tables': 'ΚΑΤΑΛΟΓΟΣ ΠΙΝΑΚΩΝ',
        'abbreviations': 'ΣΥΝΤΟΜΟΓΡΑΦΙΕΣ',
        'declaration': 'ΔΗΛΩΣΗ ΣΥΓΓΡΑΦΕΑ ΜΕΤΑΠΤΥΧΙΑΚΗΣ ΕΡΓΑΣΙΑΣ',
        'acknowledgements': 'ΕΥΧΑΡΙΣΤΙΕΣ',
        'abstract': 'ΠΕΡΙΛΗΨΗ',
        'thesis_kind': 'ΔΙΠΛΩΜΑΤΙΚΗ ΕΡΓΑΣΙΑ',
        'supervisor': 'Εισηγήτρια:',
        'committee': 'Εξεταστική Επιτροπή:',
        'am': 'Α.Μ. ',
        'blank_leaf': '(Κενό φύλλο)',
        'toc_hint': 'Πάτησε Ctrl+A και μετά F9 για να συμπληρωθούν τα '
                    'περιεχόμενα.',
        'lof_hint': 'Πάτησε F9 για να συμπληρωθεί ο κατάλογος σχημάτων.',
        'lot_hint': 'Πάτησε F9 για να συμπληρωθεί ο κατάλογος πινάκων.',
    },
}


def s(key):
    return STRINGS[LANG][key]


# Word field counter names must be ASCII and must not collide between
# editions — a shared name would make the English figure counter continue
# from the Greek one if both documents are ever open at once.
def word_lang():
    """The w:lang value. Word uses it for spell-checking and hyphenation;
    a Greek document tagged en-GB gets every word underlined in red."""
    return {'en': 'en-GB', 'el': 'el-GR'}[LANG]


def seq(kind):
    return {'chapter': 'Chap', 'figure': 'Fig', 'table': 'Tab'}[kind] \
        + LANG.upper()
