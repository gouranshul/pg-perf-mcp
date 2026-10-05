# Demo database image: official PostgreSQL 18 plus the hypopg extension
# (hypothetical indexes), which suggest_indexes uses to estimate cost savings.
#
# tzdata-legacy restores backward-compatible zone names such as Asia/Calcutta. Debian 13 moved
# them out of tzdata, and the JDBC driver sends the JVM's zone name at connect time, so without
# it clients whose OS reports a legacy name cannot connect at all.
FROM postgres:18.6

RUN apt-get update \
 && apt-get install -y --no-install-recommends "postgresql-${PG_MAJOR}-hypopg" tzdata-legacy \
 && rm -rf /var/lib/apt/lists/*
