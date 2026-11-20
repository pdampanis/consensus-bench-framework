# Builds the thesis PDFs and runs `make check`, with the same TeX Live 2023
# that Ubuntu 24.04 ships. Not covered: `make docx` (it also needs pandoc,
# python-docx, docxcompose and LibreOffice).
#
#   docker build -t thesis-tex thesis/docker
#   cd thesis
#   docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/thesis" thesis-tex make
#
# The source is mounted, not copied: edit on the host, build in the container.
FROM ubuntu:24.04@sha256:561618e2c15bf2397621dd04f96926663a3b5616c189cf7e38db7e82f5c538ea

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        make latexmk python3 fontconfig \
        texlive-xetex texlive-latex-base texlive-latex-recommended \
        texlive-latex-extra texlive-pictures texlive-plain-generic \
        texlive-publishers texlive-lang-greek texlive-fonts-recommended \
        fonts-cmu fonts-lmodern fonts-texgyre-math fonts-liberation \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# Any --user can write font and TeX caches here.
ENV HOME=/tmp
WORKDIR /thesis
CMD ["make"]
