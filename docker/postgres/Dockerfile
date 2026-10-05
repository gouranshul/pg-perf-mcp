# Demo database image: official PostgreSQL 18 plus the hypopg extension
# (hypothetical indexes), which suggest_indexes uses to estimate cost savings.
FROM postgres:18.6

RUN apt-get update \
 && apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-hypopg" \
 && rm -rf /var/lib/apt/lists/*
