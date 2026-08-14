# infra/probes — measured facts about the campaign host toolchain

Small, runnable probes that establish behaviour the harness depends on but
cannot assume. Each one exists because a golden test **structurally cannot**
catch the thing it measures: goldens pin command TEXT, not semantics (F28 is
the cautionary case — a green golden hiding a 30 s SSH channel stall).

Run them against the campaign's real OS image, not the laptop's.

## host-fault-tools-probe.sh

What the fault injector's undo commands do on **ubuntu-24.04** (the image
`infra/main.tf` pins) with `NET_ADMIN`.

```bash
docker run --rm --cap-add=NET_ADMIN \
  -v "$PWD/infra/probes/host-fault-tools-probe.sh:/probe.sh" ubuntu:24.04 \
  bash -c "apt-get update -qq >/dev/null 2>&1 && \
           DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
           iproute2 iptables stress-ng procps >/dev/null 2>&1 && bash /probe.sh"
```

**Measured 2026-08-14, Ubuntu 24.04.4 LTS:**

| Undo command | Nothing to undo | Something WAS there |
|---|---|---|
| `tc qdisc del dev <if> root` | **exit 2** — `Error: Cannot delete qdisc with handle of zero.` | **exit 0** |
| `iptables -D INPUT -s <ip> -j DROP` | **exit 1** — `iptables: Bad rule (does a matching rule exist in that chain?).` | **exit 0** |
| `pkill -f 'stress-n[g] --cpu 2'` | **exit 1** | **exit 0** |

`ip -o route get 10.0.0.99` printed
`10.0.0.99 via 172.17.0.1 dev eth0 src 172.17.0.5 uid 0` — so the interface
is the token **after `dev`**, parsed and never assumed (the parser in
`SshFaultInjector.privateIface` does exactly this).

### Why this asymmetry is load-bearing

It is what lets the **F69 pre-clean sweep** be quiet and honest at the same
time. The sweep runs BEFORE work, so on a healthy box every undo returns
non-zero — treating non-zero as a warning there would make the channel pure
noise (the F31 lesson). A **zero** exit proves state survived a previous
crashed run, which is worth shouting about, because it can mean the earlier
run's data is invalid too.

`heal()` keeps the OPPOSITE convention — it runs after a fault it knows it
applied, so there a non-zero exit is the surprise. Same commands, inverted
alarm, because the context differs. Neither uses `|| true`: that would
discard the one bit that separates the two cases.
