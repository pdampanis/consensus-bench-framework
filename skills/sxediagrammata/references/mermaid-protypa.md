# Πρότυπα Mermaid

Και τα τρία **αποδόθηκαν και ελέγχθηκαν οπτικά** πριν μπουν εδώ.

Αντιγράφεις την επικεφαλίδα **αυτούσια** σε κάθε σχήμα. Δύο γραμμές της
δεν είναι διακοσμητικές και εξηγούνται στο `SKILL.md §4`:
`edgeLabelBackground` (αλλιώς οι ετικέτες βγαίνουν ροζ) και τα
`nodeSpacing`/`rankSpacing` (αλλιώς οι κόμβοι κολλάνε).

---

## Η επικεφαλίδα — ίδια σε κάθε σχήμα

```yaml
---
config:
  theme: base
  themeVariables:
    fontFamily: "Georgia, serif"
    fontSize: "15px"
    # --- δομή: γκρι, ποτέ χρώμα σειράς ---
    lineColor: "#6E6E6E"
    transitionColor: "#6E6E6E"
    signalColor: "#6E6E6E"
    actorLineColor: "#6E6E6E"
    # --- κείμενο: πάντα σχεδόν μαύρο ---
    transitionLabelColor: "#1A1A1A"
    stateLabelColor: "#1A1A1A"
    labelColor: "#1A1A1A"
    tertiaryTextColor: "#1A1A1A"
    signalTextColor: "#1A1A1A"
    actorTextColor: "#1A1A1A"
    noteTextColor: "#1A1A1A"
    # --- ΜΗΝ ΤΟ ΣΒΗΣΕΙΣ: χωρίς αυτό το Mermaid υπολογίζει
    #     hsl(-79.4, 100%, 93%) — αρνητική απόχρωση — και βγαίνει ροζ ---
    edgeLabelBackground: "#FFFFFF"
    tertiaryColor: "#FFFFFF"
    # --- cCFT #0072B2 : οικογένεια κατάρρευσης ---
    actorBkg: "#E8F1F8"
    actorBorder: "#0072B2"
    # --- cSand #E69F00 : σημειώσεις και επιφυλάξεις ---
    noteBkgColor: "#FDF1E3"
    noteBorderColor: "#E69F00"
    sequenceNumberColor: "#FFFFFF"
  state:
    nodeSpacing: 70
    rankSpacing: 100
  sequence:
    actorMargin: 78
    messageAlign: center
    mirrorActors: false
    wrap: false
  flowchart:
    curve: linear
    nodeSpacing: 45
    rankSpacing: 85
    padding: 12
---
```

---

## Β1 — Καταστάσεις

Ευθεία αλυσίδα, **μία** ακμή επιστροφής, **καμία** αυτο-μετάβαση.

```mermaid
stateDiagram-v2
  direction LR
  state "ακόλουθος" as F
  state "υποψήφιος" as C
  state "ηγέτης"    as L

  F --> C : λήξη χρονομέτρου
  C --> L : ψήφοι πλειοψηφίας
  L --> F : νεότερη όψη

  classDef cft fill:#E8F1F8,stroke:#0072B2,stroke-width:1.6px,color:#1A1A1A
  class F,C,L cft
```

> **Τι βγήκε, και γιατί.** Η μοιρασμένη ψήφος είναι στην πραγματικότητα
> `C --> C`. Ως αυτο-μετάβαση έσερνε τις γειτονικές ακμές έξω από το
> πλαίσιο και δημιουργούσε τρεις διασταυρώσεις. Πάει στη **λεζάντα**:
> «ο υποψήφιος που δεν συγκεντρώνει πλειοψηφία ξαναρχίζει εκλογή στην
> επόμενη όψη». Ο κανόνας Κ2 υπάρχει ακριβώς γι' αυτό.
>
> Επίσης βγήκε ο δείκτης έναρξης `[*] --> F`: πρόσθετε έναν κόμβο σε
> ξεχωριστή βαθμίδα και χαλούσε την ευθυγράμμιση της σειράς.

---

## Β2 — Ακολουθία μηνυμάτων

Το `autonumber` δεν είναι διακοσμητικό: κάνει το **πλήθος των διαδρομών
μετρήσιμο**, που είναι συνήθως ο λόγος που φτιάχνεις το σχήμα.

```mermaid
sequenceDiagram
  autonumber
  participant P as πελάτης
  participant L as ηγέτης
  participant A as ακόλουθος 1
  participant B as ακόλουθος 2

  P  ->> L : αίτημα
  L  ->> A : πρόταση
  L  ->> B : πρόταση
  A -->> L : ψήφος
  B -->> L : ψήφος
  Note over L : πλειοψηφία· η εντολή δεσμεύεται
  L  ->> P : απόκριση
```

> `->>` συμπαγές = πρόταση· `-->>` διακεκομμένο = απόκριση. Η κατεύθυνση
> φαίνεται **και χωρίς χρώμα**, που είναι απαίτηση του κανόνα 3 της
> παλέτας.
>
> `mirrorActors: false` — χωρίς αυτό το Mermaid επαναλαμβάνει τους ρόλους
> και στο κάτω μέρος και το σχήμα διπλασιάζεται σε ύψος χωρίς λόγο.

---

## Β3 — Στοίβα / αρχιτεκτονική

```mermaid
flowchart LR
  CL["πελάτες"]
  subgraph CON["ΣΥΝΑΙΝΕΣΗ / ΑΤΟΜΙΚΗ ΕΚΠΟΜΠΗ"]
    AGR["συμφωνία"]
    ORD["διάταξη"]
  end
  M1["μηχανή 1"]
  M2["μηχανή 2"]
  M3["μηχανή 3"]

  CL --> CON
  CON --> M1
  CON --> M2
  CON --> M3

  classDef cli  fill:#FFFFFF,stroke:#6E6E6E,stroke-width:1.4px,color:#1A1A1A
  classDef core fill:#FDECE3,stroke:#D55E00,stroke-width:1.6px,color:#1A1A1A
  classDef sm   fill:#E8F1F8,stroke:#0072B2,stroke-width:1.6px,color:#1A1A1A
  class CL cli
  class AGR,ORD core
  class M1,M2,M3 sm
  style CON fill:#FDECE3,stroke:#D55E00,stroke-width:1.8px,color:#1A1A1A
```

> Το επίπεδο συναίνεσης είναι `cBFT`, οι μηχανές `cCFT`, οι πελάτες
> λευκοί με γκρι περίγραμμα. Ο χρωματισμός λέει **ποιο επίπεδο κάνει τη
> δύσκολη δουλειά**, δεν διακοσμεί.
>
> Το `direction TB` μέσα σε `subgraph` **αγνοείται** όταν υπάρχουν ακμές
> που περνούν το όριο. Μη στηρίζεσαι σε αυτό (κανόνας Κ1).

---

## Πώς το βλέπεις

```bash
bash scripts/render-mermaid.sh figures/katastaseis.mmd
```

Το GitHub και οι οδηγοί μελέτης το αποδίδουν μόνοι τους — αρκεί το μπλοκ
να είναι σε ```` ```mermaid ````.
