# HotStuff (asonnino/hotstuff, the PODC'19 protocol's research
# implementation) — built from SOURCE because the project publishes no
# container image. Reproducibility pin (D2, adapted — the paxi pattern):
# the SOURCE COMMIT is baked in below. HONEST CAVEAT: upstream ships NO
# Cargo.lock, so dependency versions resolve at build time within the
# semver ranges of node/Cargo.toml — the commit pin alone does NOT pin
# the dependency tree. The image id recorded at first build is therefore
# the real reproducibility anchor; record it in the campaign notes.
#
#   docker build -t hotstuff:dc01ac8 infra/hotstuff
#
# Two binaries, no entrypoint (the golden invokes them explicitly):
#   node   — keys --filename F | run --keys F --committee F
#            [--parameters F] --store PATH   (-v..-vvvv sets log level;
#            the benchmark feature's log lines feed logs.py's SUMMARY,
#            which HotStuffSummary (P2.5) parses — logs ARE its metrics)
#   client — <target ADDR> --timeout INT --size INT --rate INT
#            --nodes [ADDR...]  (waits for every --nodes address to be
#            reachable before starting; the SUT's OWN load generator —
#            the documented measurement boundary for this system)
# rust >= 1.85: the floating dep tree resolves zeroize_derive 1.5.0,
# whose manifest needs the edition2024 cargo feature (measured red on
# 1.82: "feature `edition2024` is required").
FROM rust:1.85-slim-bookworm AS build
# clang/libclang + g++/make: the store crate builds RocksDB from source
# (librocksdb-sys → bindgen needs libclang; cc needs a C++ toolchain) —
# measured red without them ("Unable to find libclang").
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates \
      clang libclang-dev g++ make \
 && rm -rf /var/lib/apt/lists/*
ARG HOTSTUFF_COMMIT=dc01ac8626a64342f6a76ae6f8914535dd090bdd
RUN git clone https://github.com/asonnino/hotstuff.git /src \
 && cd /src && git checkout -q ${HOTSTUFF_COMMIT}
WORKDIR /src/node
# The client binary requires the benchmark feature (Cargo.toml
# required-features); building from the node crate dir builds both bins.
RUN cargo build --release --features benchmark

FROM debian:bookworm-slim
COPY --from=build /src/target/release/node /usr/local/bin/node
COPY --from=build /src/target/release/client /usr/local/bin/client
