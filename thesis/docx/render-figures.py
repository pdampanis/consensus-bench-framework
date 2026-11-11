#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Renders every shared figure to PNG, once per language.

The figure GEOMETRY is shared (figures/src/*.tex) but the TEXT inside it
is not: the labels come from figures/labels-en.tex or labels-el.tex. So
each edition gets its own set of images.

The rendering uses the SAME style files as the PDF, so a figure in the
.docx and the same figure in the PDF cannot look different.

    python3 docx/render-figures.py en
"""
import hashlib
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config

TEMPLATE = r"""\documentclass[11pt,a4paper]{report}
\input{style/common}
\input{style/lang-%(lang)s}
\input{figures/labels-%(lang)s}
\input{frontmatter/meta}
\input{data/measurements}
\usepackage[active,tightpage]{preview}
\PreviewEnvironment{tikzpicture}
\setlength{\PreviewBorder}{2pt}
\begin{document}
\input{figures/src/%(fig)s}
\end{document}
"""

DPI = 300


def main():
    config.select(sys.argv[1] if len(sys.argv) > 1 else 'en')
    src = os.path.join(config.ROOT, 'figures', 'src')
    out = config.figures_dir()
    tmp = os.path.join(config.BUILD, 'figtex-%s' % config.LANG)
    os.makedirs(out, exist_ok=True)
    os.makedirs(tmp, exist_ok=True)

    # The cache must notice a change in ANY input that affects the
    # picture: the figure, the dictionary, the diagram standard and the
    # preamble. Hashing only the figure meant a font change left every
    # image stale while the script cheerfully reported "unchanged".
    deps = ''
    # EVERY file the template \inputs, not a subset. Leaving meta.tex and
    # measurements.tex out was latent only because no figure uses \meas
    # yet — and the first figure that plots a measured value is exactly
    # when a stale cache would matter most.
    for f in (os.path.join(config.ROOT, 'style', 'common.tex'),
              os.path.join(config.ROOT, 'style', 'diagrams.tex'),
              os.path.join(config.ROOT, 'style', 'lang-%s.tex' % config.LANG),
              os.path.join(config.ROOT, 'frontmatter', 'meta.tex'),
              os.path.join(config.ROOT, 'data', 'measurements.tex'),
              config.labels_file()):
        deps += open(f, encoding='utf-8').read()

    figs = sorted(f[:-4] for f in os.listdir(src) if f.endswith('.tex'))
    print('figures to render (%s): %d' % (config.LANG, len(figs)))
    rc = 0
    for fig in figs:
        body = open(os.path.join(src, fig + '.tex'), encoding='utf-8').read()
        tex = TEMPLATE % {'lang': config.LANG, 'fig': fig}
        h = hashlib.sha256((tex + body + deps).encode('utf-8')).hexdigest()[:16]
        png = os.path.join(out, fig + '.png')
        pdf = os.path.join(tmp, fig + '.pdf')
        stamp = os.path.join(tmp, fig + '.hash')
        if (os.path.exists(png) and os.path.exists(stamp)
                and open(stamp).read().strip() == h):
            print('  = %-22s unchanged' % fig)
            continue
        texpath = os.path.join(tmp, fig + '.tex')
        open(texpath, 'w', encoding='utf-8').write(tex)
        p = subprocess.run(
            ['xelatex', '-interaction=nonstopmode', '-halt-on-error',
             '-output-directory', tmp, texpath],
            cwd=config.ROOT, capture_output=True, text=True)
        if not os.path.exists(pdf):
            print('  ! %s: xelatex FAILED' % fig, file=sys.stderr)
            print('\n'.join(p.stdout.splitlines()[-25:]), file=sys.stderr)
            rc = 1
            continue
        subprocess.run(['pdftoppm', '-png', '-r', str(DPI), '-singlefile',
                        pdf, os.path.join(out, fig)], check=True)
        open(stamp, 'w').write(h)
        dim = subprocess.run(['identify', '-format', '%wx%h', png],
                             capture_output=True, text=True).stdout.strip()
        print('  + %-22s %s' % (fig, dim))
    return rc


if __name__ == '__main__':
    sys.exit(main())
