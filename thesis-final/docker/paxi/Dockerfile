# =============================================================================
# Paxi - Paxos protocol framework (github.com/ailidani/paxi)
# =============================================================================
#
# Paxi is a Go framework from Buffalo's CS department that implements multiple
# Paxos-family protocols in one codebase (Paxos, EPaxos, WPaxos, SDPaxos, ...).
# It is the tool used by the SIGMOD 2019 paper "Dissecting the Performance of
# Strongly-Consistent Replication Protocols" — a direct fit for our thesis's
# "selected implementations, representative scenarios" commitment.
#
# Paxi produces three binaries when built:
#   server - one replica instance, selects protocol via -algorithm=<name>
#   client - built-in benchmark client (throughput/latency measurement)
#   cmd    - CLI tool for single Get/Set operations (debugging)
#
# We build all three and use `server` for replicas and `client` for the
# benchmark. Single image is reused for multiple services in the compose file.
# =============================================================================

FROM golang:1.22-alpine AS build

RUN apk add --no-cache git build-base bash

WORKDIR /src
# Clone Paxi at a pinned commit. Using master for now; if the build breaks
# in the future, pin to a known-working SHA with `RUN git checkout <sha>`.
RUN git clone https://github.com/ailidani/paxi.git .

# Paxi's bin/build.sh compiles server, client, and cmd into bin/.
RUN cd bin && bash build.sh && ls -la

# ---- runtime image ----
FROM alpine:3.19

RUN apk add --no-cache bash ca-certificates

# Copy the three built binaries
COPY --from=build /src/bin/server /usr/local/bin/paxi-server
COPY --from=build /src/bin/client /usr/local/bin/paxi-client
COPY --from=build /src/bin/cmd    /usr/local/bin/paxi-cmd

# Paxi example config lives in the source at /src/config.json; we also
# keep it available inside the image so a service can fall back to it.
COPY --from=build /src/config.json /etc/paxi/config.json

# Create a working dir with write access
RUN mkdir -p /work && chmod 777 /work
WORKDIR /work

# Default entrypoint is the server; override to run client or cmd.
ENTRYPOINT ["/usr/local/bin/paxi-server"]
