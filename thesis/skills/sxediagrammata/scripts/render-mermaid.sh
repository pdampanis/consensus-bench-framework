#!/usr/bin/env bash
# render-mermaid.sh — αποδίδει .mmd σε PNG (για κοίταγμα) και PDF.
#
#   bash scripts/render-mermaid.sh figures/katastaseis.mmd [πλάτος]
#
# ΠΡΟΣΟΧΗ: το PDF ΔΕΝ έχει τη γραμματοσειρά του κειμένου (CMU Serif).
# Χρησιμοποίησέ το για πρόχειρα και για τους οδηγούς μελέτης. Για την
# κατάθεση, μετέφερε σε TikZ — βλ. references/tikz-protypa.tex.
set -euo pipefail
[ $# -ge 1 ] || { echo "χρήση: $0 <αρχείο.mmd> [πλάτος]"; exit 1; }
SRC="$1"; W="${2:-1100}"
[ -f "$SRC" ] || { echo "δεν υπάρχει: $SRC"; exit 1; }

DIR="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$DIR/references/puppeteer.json"

command -v mmdc >/dev/null || {
  echo "λείπει το mmdc.  Εγκατάσταση:  npm i -g @mermaid-js/mermaid-cli"
  exit 1; }

# Το mmdc χρειάζεται Chromium και δεν κατεβάζει δικό του παντού.
# Αν το puppeteer.json δεν δείχνει σε εκτελέσιμο, ψάξε έναν.
need_cfg=1
if [ -f "$CFG" ]; then
  ep=$(sed -n 's/.*"executablePath"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CFG")
  [ -n "${ep:-}" ] && [ -x "$ep" ] && need_cfg=0
fi
if [ "$need_cfg" = 1 ]; then
  for c in /opt/pw-browsers/chromium-*/chrome-linux/chrome \
           /usr/bin/chromium /usr/bin/chromium-browser \
           /usr/bin/google-chrome /usr/bin/google-chrome-stable \
           "$HOME/.cache/puppeteer"/chrome/*/chrome-linux64/chrome; do
    if [ -x "$c" ]; then
      printf '{"executablePath":"%s","args":["--no-sandbox","--disable-dev-shm-usage"]}\n' \
        "$c" > "$CFG"
      echo "βρέθηκε Chromium: $c"
      need_cfg=0; break
    fi
  done
fi
[ "$need_cfg" = 0 ] || {
  echo "δεν βρέθηκε Chromium. Όρισέ τον στο $CFG"; exit 1; }

BASE="${SRC%.mmd}"
mmdc -p "$CFG" -i "$SRC" -o "$BASE.png" -w "$W" -b white
mmdc -p "$CFG" -i "$SRC" -o "$BASE.pdf"          -b white

echo
echo "→ $BASE.png   ΚΟΙΤΑΞΕ ΤΟ. Ελέγχεις με τα μάτια:"
echo "    Κ5  καμία διασταύρωση ακμών"
echo "    Κ4  το πολύ επτά κόμβοι"
echo "    οι ετικέτες ακμών είναι ΜΑΥΡΕΣ (αν είναι ροζ, λείπει"
echo "        το edgeLabelBackground από την επικεφαλίδα)"
echo "→ $BASE.pdf   πρόχειρο μόνο· η γραμματοσειρά δεν ταιριάζει"
