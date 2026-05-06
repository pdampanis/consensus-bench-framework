# =============================================================================
# HotStuff (asonnino/hotstuff, 2-chain Diem variant)
# =============================================================================
#
# Why this design. The asonnino/hotstuff repo ships its own benchmarking
# harness written in Python + Fabric. Rather than reimplementing the
# key-generation and committee-assembly logic (which is non-trivial and
# specific to this codebase), we vendor the entire repo into the image
# and invoke its own `fab local` entry point when we want to run a
# benchmark. This keeps our integration layer minimal and ensures that
# what we measure is *exactly* what the published HotStuff reference
# implementation measures — a significant methodological strength.
#
# Why main branch over 3-chain. The main branch implements the 2-chain
# Diem variant, which is what HotStuff actually became in production.
# The 3-chain branch implements the original PODC 2019 paper variant
# but is less actively maintained. Our thesis's literature review
# already covers the 3-chain paper; measuring the 2-chain industrial
# deployment is arguably more interesting and what we are citing in the
# evolution narrative.
#
# Image contents:
#   /usr/local/bin/fab          fabric executable
#   /usr/local/bin/node         HotStuff validator binary
#   /usr/local/bin/client       HotStuff client binary
#   /opt/hotstuff/              vendored repo (incl. benchmark/fabfile.py)
#   /opt/hotstuff-entrypoint.sh thin wrapper that cd's to benchmark/ and
#                               runs `fab local` with customizable params
#                               via environment variables
# =============================================================================

# -------- build stage --------
FROM rust:1.75-slim-bookworm AS build

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        git clang pkg-config libssl-dev build-essential ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Clone the asonnino/hotstuff main branch (2-chain Diem variant).
# For reproducibility in production campaigns, pin to a commit SHA by
# replacing the branch name below with: --branch <sha> or adding a
# subsequent `RUN git checkout <sha>`. Keeping this as a moving branch
# means newer commits get picked up on image rebuild; pinning is safer
# for final thesis-grade measurements.
RUN git clone --branch main --depth 1 https://github.com/asonnino/hotstuff.git .

# Build the two binaries in release mode.
# The build produces target/release/node and target/release/client.
RUN cargo build --release --bin node --bin client

# -------- runtime stage --------
FROM python:3.11-slim-bookworm

# Runtime deps: libssl3 for Rust binaries; tmux and clang+libssl for
# anything fabric might shell out to (it expects a working environment
# similar to what the original README describes).
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libssl3 ca-certificates curl tmux procps netcat-openbsd jq && \
    rm -rf /var/lib/apt/lists/*

# Copy the built binaries and the whole vendored repo (including the
# benchmark/ directory with its fabfile.py).
COPY --from=build /src/target/release/node   /usr/local/bin/node
COPY --from=build /src/target/release/client /usr/local/bin/client
COPY --from=build /src /opt/hotstuff

# Install the fabric python dependencies.
RUN cd /opt/hotstuff/benchmark && \
    pip install --no-cache-dir -r requirements.txt

# Make sure binaries are executable.
RUN chmod +x /usr/local/bin/node /usr/local/bin/client

# Copy the in-container benchmark runner, fault injector, and LE probe.
COPY hs-run.sh      /opt/hs-run.sh
COPY hs-inject.sh   /opt/hs-inject.sh
COPY hs-le-probe.sh /opt/hs-le-probe.sh
RUN chmod +x /opt/hs-run.sh /opt/hs-inject.sh /opt/hs-le-probe.sh

# Install iproute2 + iptables so hs-inject.sh can do network-layer
# fault injection inside the container. Requires the container to run
# with --cap-add=NET_ADMIN (set in compose/hotstuff.yml).
RUN apt-get update && \
    apt-get install -y --no-install-recommends iproute2 iptables && \
    rm -rf /var/lib/apt/lists/*

# Working directory for logs, keys, committee files.
RUN mkdir -p /work && chmod 777 /work
WORKDIR /work

# Default command is bash — callers override with either `fab local`
# (for a one-shot benchmark) or by calling the binaries directly.
CMD ["bash"]
