#!/usr/bin/env python3
"""corpus.py — the corpus is a claim, so make it checkable.

`thesis/bib/references.bib` says of each source how it was verified, and
26 entries claim VERIFIED-PDF — "I read this in the corpus". Nothing
until now could test that claim: the bib cites `[corpus N]` indices with
no mapping to a filename, and a rename or a re-download broke the link
silently.

    corpus.py manifest   regenerate corpus/MANIFEST.tsv from what is on disk
    corpus.py verify     disk vs manifest vs bib — the check for CI
    corpus.py fetch      download the OPEN-ACCESS sources that are missing

`fetch` downloads from arXiv and IACR ePrint only. Everything else —
ACM, IEEE, Springer — is listed with its DOI for you to fetch through
your institution. This script does not work around a paywall.
"""
import argparse
import hashlib
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "corpus"
MANIFEST = CORPUS / "MANIFEST.tsv"
BIB = ROOT / "thesis" / "bib" / "references.bib"
COLUMNS = ["file", "sha256_16", "bytes", "identifier", "title_page1", "bibkey"]


def page1(pdf: Path) -> str:
    try:
        return subprocess.run(["pdftotext", "-f", "1", "-l", "1", str(pdf), "-"],
                              capture_output=True, text=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def identify(text: str) -> str:
    """The strongest identifier printed on the first page, or ''."""
    m = re.search(r"arXiv:\s*(\d{4}\.\d{4,5})", text)
    if m:
        return "arXiv:" + m.group(1)
    m = re.search(r"(?:doi\.org/|DOI:?\s*)(10\.\d{4,9}/[^\s,;)]+)", text, re.I)
    if m:
        return "doi:" + m.group(1).rstrip(".")
    if re.search(r"eprint\.iacr\.org|Cryptology ePrint", text, re.I):
        return "iacr"
    return ""


def scan():
    rows = []
    for f in sorted(CORPUS.glob("*.pdf")):
        raw = f.read_bytes()
        text = page1(f)
        title = next((s for s in (l.strip() for l in text.splitlines())
                      if len(s) > 12 and not s.lower().startswith("arxiv")), "")
        rows.append([f.name, hashlib.sha256(raw).hexdigest()[:16], str(len(raw)),
                     identify(text), title[:70], ""])
    return rows


def write_manifest(rows):
    MANIFEST.write_text("\t".join(COLUMNS) + "\n"
                        + "".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")


def read_manifest():
    if not MANIFEST.exists():
        return []
    lines = MANIFEST.read_text(encoding="utf-8").splitlines()
    return [l.split("\t") for l in lines[1:] if l.strip()]


def bib_pdf_claims():
    """bibtex keys whose `verification` field claims VERIFIED-PDF."""
    if not BIB.exists():
        return []
    text = BIB.read_text(encoding="utf-8")
    out = []
    for entry in re.split(r"(?m)^@", text)[1:]:
        key = re.match(r"\w+\{([^,]+),", entry)
        if key and "VERIFIED-PDF" in entry:
            out.append(key.group(1).strip())
    return out


def cmd_manifest():
    """Regenerating must never discard the bibkey column: it is the one
    thing here a person filled in by hand."""
    known = {r[0]: (r[1], r[5] if len(r) > 5 else "") for r in read_manifest()}
    rows = scan()
    kept = 0
    for r in rows:
        sha, key = known.get(r[0], ("", ""))
        if key and sha == r[1]:          # same file, same content -> same paper
            r[5] = key
            kept += 1
    write_manifest(rows)
    mapped = sum(1 for r in rows if r[5])
    print(f"{MANIFEST.relative_to(ROOT)}: {len(rows)} files, {mapped} mapped to a "
          f"bibkey ({kept} carried forward), {len(rows) - mapped} unmapped")
    return 0


def cmd_verify():
    disk = {r[0]: r for r in scan()}
    recorded = {r[0]: r for r in read_manifest()}
    problems = 0

    if not recorded:
        print("FAIL  no MANIFEST.tsv — run `corpus.py manifest` and commit it")
        return 1

    for name in sorted(set(disk) | set(recorded)):
        if name not in recorded:
            print(f"FAIL  {name}: on disk, not in the manifest")
            problems += 1
        elif name not in disk:
            print(f"FAIL  {name}: in the manifest, not on disk")
            problems += 1
        elif disk[name][1] != recorded[name][1]:
            print(f"FAIL  {name}: content changed "
                  f"({recorded[name][1]} -> {disk[name][1]})")
            problems += 1

    # Byte-identical copies are not an error, but a rename that leaves two
    # names for one file makes `[corpus N]` ambiguous. Say so.
    by_sha = {}
    for r in disk.values():
        by_sha.setdefault(r[1], []).append(r[0])
    dups = {s: n for s, n in by_sha.items() if len(n) > 1}
    for sha, names in sorted(dups.items()):
        print(f"WARN  duplicate content ({sha}): " + " | ".join(sorted(names)))

    mapped = {r[5] for r in recorded.values() if len(r) > 5 and r[5]}
    unmapped = [r[0] for r in recorded.values() if len(r) < 6 or not r[5]]
    claims = bib_pdf_claims()
    orphan_claims = sorted(set(claims) - mapped)

    n_rows_mapped = sum(1 for r in recorded.values() if len(r) > 5 and r[5])
    print(f"\ncorpus: {len(disk)} files, {len(by_sha)} distinct; {n_rows_mapped} rows "
          f"carry a bibkey, naming {len(mapped)} distinct papers")
    for k in orphan_claims:
        print(f"WARN  {k}: the bib claims VERIFIED-PDF, but no corpus file is "
              f"mapped to it")
    if unmapped:
        print(f"WARN  {len(unmapped)} file(s) have no bibkey — fill the last "
              f"column of MANIFEST.tsv: " + ", ".join(sorted(unmapped)[:3])
              + (" ..." if len(unmapped) > 3 else ""))
    print(f"{'FAIL' if problems else 'OK'}  {problems} problem(s), {len(dups)} duplicate group(s)")
    return 1 if problems else 0


def bib_entries():
    """(key, arxiv_id|None, iacr_report|None, doi|None) for every bib entry."""
    if not BIB.exists():
        return []
    out = []
    for entry in re.split(r"(?m)^@", BIB.read_text(encoding="utf-8"))[1:]:
        m = re.match(r"\w+\{([^,]+),", entry)
        if not m:
            continue
        arx = re.search(r"arXiv:\s*(\d{4}\.\d{4,5})", entry)
        iacr = re.search(r"Report\s+(\d{4}/\d+)", entry)
        doi = re.search(r"doi\s*=\s*\{([^}]+)\}", entry)
        out.append((m.group(1).strip(),
                    arx.group(1) if arx else None,
                    iacr.group(1) if iacr else None,
                    doi.group(1).strip() if doi else None))
    return out


def cmd_fetch(dry_run):
    """Three buckets, and the order matters: a paper the corpus already
    holds must never be reported as one to chase through a paywall."""
    rows = read_manifest()
    have_ids = {r[3] for r in rows if r[3]}
    # The bibkey column is the STRONGER signal — it is the one a person
    # filled in — and ignoring it told you to chase a dozen papers the
    # corpus already holds.
    have_keys = {r[5] for r in rows if len(r) > 5 and r[5]}
    present, fetchable, manual, unknown = [], [], [], []
    for key, arx, iacr, doi in bib_entries():
        ids = set()
        if arx:
            ids.add("arXiv:" + arx)
        if doi:
            ids.add("doi:" + doi)
        # NOT iacr: identify() records it as a bare "iacr" with no report
        # number, so one IACR paper in the corpus would mark every IACR
        # entry present. bibkey covers those instead.
        if key in have_keys or (ids & have_ids):
            present.append(key)
        elif arx:
            fetchable.append((key, f"https://arxiv.org/pdf/{arx}", f"{arx}.pdf"))
        elif iacr:
            fetchable.append((key, f"https://eprint.iacr.org/{iacr}.pdf",
                              iacr.replace("/", "-") + ".pdf"))
        elif doi:
            manual.append((key, doi))
        else:
            unknown.append(key)

    print(f"already in the corpus (bibkey or identifier): {len(present)} of "
          f"{len(present) + len(fetchable) + len(manual) + len(unknown)} bib entries")
    for key, url, name in fetchable:
        target = CORPUS / name
        if target.exists():
            print(f"have  {name}  ({key})")
            continue
        if dry_run:
            print(f"would fetch  {name}  <- {url}  ({key})")
            continue
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "corpus.py/1.0"})
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read()
            if not body.startswith(b"%PDF"):
                print(f"SKIP  {name}: not a PDF ({key})")
                continue
            target.write_bytes(body)
            print(f"got   {name}  ({len(body)} bytes, {key})")
        except Exception as e:                       # noqa: BLE001 — report, continue
            print(f"FAIL  {name}: {e}  ({key})")

    if manual:
        print(f"\n{len(manual)} behind a paywall — fetch through your institution."
              " This script will not work around one:")
        for key, doi in sorted(manual):
            print(f"  {key:32s} https://doi.org/{doi}")
    if unknown:
        print(f"\n{len(unknown)} with no identifier in the bib at all "
              "(no doi, no arXiv, no report number):")
        for key in sorted(unknown):
            print(f"  {key}")
    if dry_run:
        print("\n(dry run — nothing downloaded)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["manifest", "verify", "fetch"])
    ap.add_argument("--dry-run", action="store_true", help="fetch: list, download nothing")
    a = ap.parse_args()
    if a.command == "manifest":
        return cmd_manifest()
    if a.command == "verify":
        return cmd_verify()
    return cmd_fetch(a.dry_run)


if __name__ == "__main__":
    sys.exit(main())
