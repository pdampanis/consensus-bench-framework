# =============================================================================
# le-probe - small Python container for all the thesis's probe needs
# =============================================================================
# Four scripts ship in this image:
#   le_probe.py    - leader-election CDF trials against any system
#   cmt_bench.py   - CometBFT throughput/latency driver (HTTP /broadcast_tx_commit)
#   paxi_bench.py  - Paxi throughput/latency driver (HTTP PUT)
#
# All drivers emit output in the same kafka-producer-perf-test format so
# parse_results.sh normalizes them into the same CSVs. No entrypoint: the
# caller picks which script via `docker run ... le-probe:latest python3 /X.py`.
# =============================================================================
FROM python:3.11-alpine

RUN apk add --no-cache librdkafka librdkafka-dev build-base && \
    pip install --no-cache-dir \
        confluent-kafka==2.5.0 \
        etcd3==0.12.0 \
        grpcio==1.59.3 \
        protobuf==4.25.8

COPY le_probe.py   /le_probe.py
COPY cmt_bench.py  /cmt_bench.py
COPY paxi_bench.py /paxi_bench.py

ENTRYPOINT []
