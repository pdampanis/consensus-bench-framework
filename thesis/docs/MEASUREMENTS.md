# Where your own data goes

This file answers one question: **what does the benchmark campaign have
to produce, and which sentence of the thesis is waiting for it?**

It is not a wish list. Every entry below corresponds to a
`\pendingmeasurement` in `data/measurements.tex`, and `make data` prints
the same list from that file, so the two cannot drift apart.

---

## The rule that makes this file necessary

A number that has not been measured cannot be typed into the text. It is
declared once, in `data/measurements.tex`, and the text asks for it by
name:

```latex
etcd reaches \meas{etcd.tput.n3} requests per second at three nodes.
```

Until you fill the value in, the page prints a red **[PENDING]**.
Nobody — not you at two in the morning, not an examiner — can mistake a
placeholder for a result.

```
\pendingmeasurement{etcd.tput.n3}            before
\declaremeasurement{etcd.tput.n3}{12\,400}   after
```

---

## 1. Comparability first (RQ3) — four values, and they come first

| key | what it is |
|-----|------------|
| `env.vm.spec`     | the machine every system ran on, stated once |
| `env.network.rtt` | the measured round-trip time between nodes |
| `env.repeats`     | how many runs per configuration |
| `env.cv.max`      | the worst coefficient of variation across all runs |

**Why these come before any performance number.** The abstract already
claims that "every comparative claim carries its level of
comparability". These four are that claim's evidence. `env.cv.max` is
the one an examiner will ask about: if the run-to-run spread is 15% and
you report that protocol A beats protocol B by 8%, you have reported
noise. Measure it, and if it is large, say so and widen the claim.

**Where it goes in the text:** the methodology chapter, in one short
table, before any result. A named environment is what lets the reader
decide whether your numbers and a published paper's numbers are even
about the same thing — which is the gap Chapter 1 §1.3 says the
literature has.

---

## 2. Throughput and latency (RQ4) — 15 values

| keys | shape |
|------|-------|
| `etcd.tput.n3/n5/n7`, `tendermint.tput.n4/n7`, `hotstuff.tput.n4/n7` | requests/s at saturation |
| `etcd.p50.n3/n5`, `etcd.p99.n3/n5` | milliseconds, median and tail |
| `tendermint.p50.n4`, `tendermint.p99.n4`, `hotstuff.p50.n4`, `hotstuff.p99.n4` | milliseconds |

**The diagram this becomes.** One `dgbarchart`, throughput per
implementation, grouped by cluster size — the same standard as
`figures/src/fig-scaling.tex`, which means the same palette, the same
width, the same fonts. Do not invent a chart type for it; the standard
exists precisely so that the reader who has understood Figure 1.3 does
not have to learn a second visual language on page 60.

**A second diagram, and this one matters more.** Median *and* tail in
one picture: p50 and p99 as two series over cluster size. The median is
what a protocol advertises; the tail is what a user experiences. A
figure that shows the two diverging is worth more than a paragraph
saying they do.

**Which existing claim it tests.** Chapter 1 §1.3 argues that published
comparisons do not transfer between papers. Your own p50/p99 pair, taken
on one instrument, is the constructive half of that argument: here is
what the comparison looks like when the environment *is* held fixed.

**The honest caveat that has to travel with it.** What you measure is an
implementation at a named version and configuration, not the protocol.
The abstract already says this. Repeat it in the caption of the figure,
not only in the surrounding prose — captions travel, prose does not.

---

## 3. What a leader failure costs (RQ4) — 6 values

| keys | what it is |
|------|------------|
| `etcd.failover.ms`, `tendermint.failover.ms`, `hotstuff.failover.ms` | milliseconds from the failure to the next committed request |
| `*.failover.losspct` | throughput lost over that window, as a percentage |

**Why this is the most valuable measurement in the campaign.**
Chapter 1 §1.2 and Table 1.1 discuss view-change cost as
$O(n)$ versus $O(n^2)$, from the literature, with the reservation that
the source itself states. That is a complexity class. A complexity class
does not tell a reader whether a failover is invisible or whether it
stalls their service for four seconds. These six numbers turn the
formula into an experience, and no source in the corpus gives them for
your environment.

**The diagram this becomes.** A `dgdata` time series: throughput against
time, with the failure marked. One line per implementation, the standard
`dgcycle` giving each its own mark and dash so the figure survives being
printed in black and white. The dip *is* the finding; a bar chart of
mean failover time hides exactly the thing worth showing.

**Which existing claim it tests.** Table 1.1 reproduces four positions
on the view-change axis from a preprint marked `\prep`, together with
the reservation that the same source states. Your measurement is the
first evidence in this thesis that is not secondary. Say so plainly —
that is a contribution, and understating it is as dishonest as
overstating it.

---

## 4. Scaling (RQ5) — 3 values

`scaling.etcd.dropn3n7pct`, `scaling.tendermint.dropn4n7pct`,
`scaling.hotstuff.dropn4n7pct`: the percentage of throughput lost as the
cluster grows.

**This one is aimed at an existing figure.** `fig-scaling` currently
shows percentage degradation for seven Byzantine protocols in two
deployment topologies, taken from the literature. Your three numbers are
the same quantity, measured by you, on three implementations. They
belong **beside** that figure, not instead of it — the interesting
sentence is the comparison between the published shape and yours.

**State the limit before an examiner does.** Chapter 1 already concedes
that "the range of cluster sizes examined experimentally is small
relative to the scales at which asymptotic behaviour appears". Three to
seven nodes cannot confirm or refute an asymptotic claim. What it can do
is show the *direction* and whether a knee appears early. Claim that,
and nothing more.

---

## 5. The predictions — 3 values, and the timing is the point

`pred.tput.ranking`, `pred.scaling.shape`, `pred.failover.ranking`.

The abstract says: *"Predictions are registered before the measurements
are taken."* That sentence is a claim about **process**, and it is
checkable in exactly one way: the git history of this repository.

So:

1. Design the campaign.
2. Fill in these three, and **commit them on their own**, with a message
   that says what they are.
3. Only then run the experiments.

A prediction committed in the same commit as the results it "predicts"
is worth nothing, and an examiner who thinks to check will find it in
thirty seconds. Done properly, this is one of the strongest things in
the thesis: very few student projects can point at a timestamp.

If a prediction turns out wrong, **keep it and say so**. A wrong
registered prediction that you then explain is evidence of method. A
quietly deleted one is the opposite.

---

## 6. What must change in the text once the data exist

| where | what happens |
|-------|--------------|
| Abstract (both languages) | the marked placeholder becomes 2-3 sentences of findings. `make check` reports the word count; the Guidelines want 250-300 |
| §1.5 contribution (c) | already promises "pre-registered predictions" — it becomes true, and can point at the chapter |
| Table 1.1 | gains a column, or a companion table, with your own failover numbers next to the literature's complexity classes |
| `fig-scaling` | gains a companion figure with your three measured drops |
| Methodology chapter | the four RQ3 values, in a table, before anything else |
| Evaluation chapter | the four figures described above |
| Conclusions | what the data support, and what they do not |

`make data` will tell you when the last `[PENDING]` is gone. Until then
it is the honest state of the work, and there is nothing wrong with
handing a supervisor a draft that says [PENDING] in red — that is a
draft that cannot lie to them.

---

## 7. Two things to decide before measuring, not after

**Open loop or closed loop.** The abstract commits to both: open loop
for throughput and latency tails, closed loop for the saturation search.
This is the right choice, and `schroeder2006openclosed`
(marked `\unver` — its bibliographic data were taken from another work's
reference list) is the source for why the two differ. Make sure the load
generator actually does what the abstract says it does. If it does not,
change the abstract, not the description of the experiment.

**What counts as "the same workload".** Three systems with different
APIs cannot run an identical client. Write down, before you measure,
what you are holding fixed — request size, key distribution, read/write
ratio — and what you are letting differ. That paragraph is what makes
the comparison defensible, and it is much harder to write honestly
afterwards.
