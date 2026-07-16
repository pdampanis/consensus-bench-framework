# Paxi (Paxos/EPaxos framework, SIGMOD'19) — built from source because the
# project publishes NO container image. Reproducibility pin (D2, adapted):
# a locally built image has no registry digest, so the pin is the SOURCE
# COMMIT baked in here + the Dockerfile itself; `docker build` of this file
# alone reproduces the image byte-for-byte modulo base layers (which ARE
# digest-pinnable if ever needed for the campaign — record image IDs in the
# campaign notes at first build).
#
#   docker build -t paxi:6823d0b infra/paxi
#
# The binary serves every algorithm the thesis needs (-algorithm paxos|epaxos);
# config comes from a generated /config.json (LocalDockerProvider copies it in).
FROM golang:1.21-alpine AS build
RUN apk add --no-cache git
ARG PAXI_COMMIT=6823d0b0fb1690a906391bcd5b4e0b01486ea2bd
RUN git clone https://github.com/ailidani/paxi.git /src \
 && cd /src && git checkout -q ${PAXI_COMMIT}
WORKDIR /src
RUN go build -o /paxi-server ./server

FROM alpine:3.20
COPY --from=build /paxi-server /usr/local/bin/paxi-server
ENTRYPOINT ["/usr/local/bin/paxi-server"]
