# What two reviews found, what was fixed, and what is still open

Two independent reviews were run against this repository: one reading
the two written chapters as an examination committee would, one auditing
the build system for claims the code makes and does not keep. Their
findings are recorded here rather than quietly absorbed, because the
list of what is *still* wrong is more useful than the list of what was
fixed.

Everything under **Fixed** is in the git history. Everything under
**Open** is a decision for you, and most of it is a question a committee
can ask.

---

## Fixed

### In the thesis

| what | where |
|------|-------|
| Table 2.1's first row was labelled "crash with detection (fail-stop)" — Schneider's sense — for a row that DLS define with no detection clause. With reliable detection the ∞ in the asynchronous column would be wrong | §2.1 |
| The Figure 1.2 caption said the Bedrock survey attributes 3f+1 to Pease 1980 and Lamport 1982. It cites five works; Pease 1980 is not among them. The 1980 attribution is this thesis's own and now says so | §1.2 |
| FLP stated without the word *deterministic* | §1.1 |
| A 2019 wiki page cited as the source for a 2022 release date | §1.2 |
| "bijective function" where the source says *one-to-one* | §1.2 |
| "almost three decades" counted from a date §1.6 refuses to use as evidence | §2.5 |
| The HotStuff authenticator bound written as O(n²) "that is" O(fn), which reverses it | §2.4.3 |
| `\unver` carrying three meanings at once | throughout |
| The timeline rule said "no refereed publication exists" while the same caption named Tendermint's | §1.2 |
| Three sections opened straight into a subsection with no orientation | §1.1, §2.4, §2.6 |

### In the checks

Nine of these were checks that could not fail, or messages that claimed
more than the code did. The full list is in the commit message; the two
that mattered most:

- **`cite-check` crashed** on a citation to a missing key — the exact
  defect C01 exists to catch — and took nine other checks down with it.
- **Nothing checked the numbers a reader sees before pressing F9.** A
  cached "9.9" where LaTeX computed "1.2" passed every check in the
  suite. `V20` now compares every cached cross-reference against the
  `.aux`, and the self-test proves it fires.

---

## Open — and these are the committee's questions

### 1. Four things a background chapter on this topic is expected to have

None of these is an error; each is an absence a distributed-systems
examiner will notice.

- **A formal statement of the consensus problem.** Agreement, validity,
  integrity and termination are never defined. §2.4.1 says FLP's
  non-triviality is "weaker than validity" — a term the thesis does not
  define anywhere.
- **Failure detectors.** No Chandra–Toueg, no Ω, no "weakest failure
  detector for consensus". A chapter that rests on "silence does not
  differ from delay" and on fail-stop detectability cannot leave this
  out, and it is the direct answer to the Table 2.1 question above.
- **The consensus ⇄ atomic broadcast equivalence.** §2.3 asserts they
  are "not the same" — true of the definitions — without the reducibility
  result that makes the point precise.
- **CAP.** §2.6.1 states the safety/liveness trade-off under partition
  in exactly CAP's terms, with no citation and no Gilbert–Lynch.

### 2. Two places where the plan contradicts the stated gap

- **§1.3 defines the open problem as measuring "the production
  implementations — the ones that are really deployed".**
  `data/measurements.tex` schedules HotStuff, whose implementations are
  research prototypes. Either the gap statement narrows, or HotStuff is
  justified as a deliberate exception. Both are defensible; silence is
  not.
- **Durability is not mentioned anywhere.** `fsync`, `WAL`, `snapshot`,
  `lease` and `reconfiguration` appear zero times in either chapter or in
  the measurement register. The campaign compares etcd, which fsyncs its
  write-ahead log, against BFT prototypes that may not. That is a
  first-order confound for every throughput and p99 number you are about
  to collect — and RQ3 exists precisely to catch this class of thing.
  Decide the policy before measuring, and write it down.

### 3. PBFT has not been read

`bib/references.bib` says so honestly: `castro1999pbft` is marked as not
read, and so is `junqueira2011zab`. §1.6 promises that "each algorithm is
described from its primary source". Both are cited in Table 1.1 and in
the timeline. There is no marker for "refereed, verified, but unread" —
the bibliography knows, and the page does not. Either read them, or add
the distinction to the marker system.

### 4. Scope: the thesis stops at 2023

No randomized or asynchronous BFT beyond one sentence (Ben-Or,
HoneyBadger), and no DAG-based protocols (Narwhal/Tusk, Bullshark). For
a thesis whose title word is *Evolution*, the boundary needs an argued
sentence, not silence.

### 5. Smaller things, all real

- §2.5 opens "A quorum is the smallest set… the only property it needs is
  intersection", and step 1 of the derivation immediately below is an
  *availability* constraint, not intersection. Supersets of quorums are
  also quorums.
- §2.3.1 attributes 3f+1 to Pease 1980 in a bullet, while §2.4.4 argues
  the 3f+1 this thesis uses comes from partial synchrony. The bullet
  should point at DLS.
- §2.4.3 says "many secondary sources state this wrongly" with no
  citation.
- §2.6.1's safety/liveness sentences are Lamport's formulation, uncited.
- Table 1.1's column header says "Responsive"; the source's column is
  "Responsive **view-change**". Dropping two words broadens a claim in a
  table declared as a reproduction.
- Table 2.4 converts phases into delays for PBFT and HotStuff and leaves
  the one cross-family row — Multi-Paxos/Raft — unconverted.
- Responsiveness is attributed to the Tendermint paper, which never uses
  the word. The concept comes from HotStuff and HotStuff-2.
- §1.4 cites "Appendix A"; there are no appendices yet.
- §1.5 addresses the supervisor inside the body text ("it is put
  explicitly to the supervisor"). That belongs in a cover note.
- §2.4.2's boxed aside is written in second person, as an exam crib.
- A handful of self-referential sentences carry no content: "This section
  is short and it carries weight", "It is the most compressed result of
  the chapter", "and the arbitrariness shows".
- §2.5.2's title, "Intersection is required less than we thought", is
  ungrammatical; it means *in fewer places* than we thought.
- "third publications" (§1.4) is a Greek-ism for third-party.

### 6. The abstract promises an experiment in the present tense

It says the evaluation *uses* one instrument, *runs* open and closed
loop, and that "predictions are registered before the measurements are
taken". Every prediction is still `\pendingmeasurement`. The
`[PENDING]` machinery covers the numbers; it does not cover the
methodological claims around them. Either move them to the future tense
until the campaign runs, or run the campaign.

---

## What both reviews called genuinely strong

Worth knowing, because you will be asked what the contribution is.

- **The verification discipline is real and it caught real errors.** All
  four cases in §1.6 were re-checked against the PDFs and all four are
  exactly as described — including one where the corpus *filename* is
  wrong and the thesis is right. Several bibliography entries record
  their own corrections; one removed a `year` field because its source
  "is not evidence". That is R1 enforced against the author's own draft.
- **§2.4.1 on FLP is better than most published summaries.** That
  "validity" never appears in the paper, that the fault model is crash
  and this makes the result *stronger*, and that termination is required
  of only *some* process — all three verified, all three routinely got
  wrong elsewhere.
- **Every number reproduced from the Bedrock survey is exact, and so is
  every environmental condition attached to it** — seven LAN
  percentages, three geo percentages, the SBFT/HotStuff reversal, the
  hardware line, batch size, payload, closed loop, mean of five runs.
  The two reservations in the Figure 1.3 caption are real and are what
  an honest reader of that appendix would flag.
- **The §1.3 argument** — EPaxos's original claim, Tollman's
  re-evaluation, Matte's change of metric — is the strongest analytical
  passage in Chapter 1, every quotation in it is verbatim-correct, and
  the conclusion it draws (the metric is part of the conclusion, and must
  be declared in advance) is the right one.
- **The quorum derivation and the 2t+1 / 3f+1 counting trap** are both
  correct, both non-obvious, and both the kind of thing that separates a
  thesis from a literature review.
- **The `[PENDING]` mechanism** makes the unwritten chapters visibly
  unwritten rather than quietly plausible.
