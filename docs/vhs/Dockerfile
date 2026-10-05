# VHS plus the tools demo/tour.sh needs. Only used to record docs/demo.gif.
FROM ghcr.io/charmbracelet/vhs:v0.12.1

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl jq \
 && rm -rf /var/lib/apt/lists/*
