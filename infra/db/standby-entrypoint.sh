#!/bin/bash
set -euo pipefail
: "${PGDATA:=/var/lib/postgresql/data}"
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "[db-standby] Waiting for primary PostgreSQL..."
  until pg_isready -h postgres-primary -p 5432 -U replicator; do sleep 2; done
  echo "[db-standby] Taking initial base backup from primary..."
  mkdir -p "$PGDATA"
  chmod 700 "$PGDATA"
  PGPASSWORD="${REPLICATION_PASSWORD:-replica_password}" pg_basebackup \
    -h postgres-primary -p 5432 -U replicator -D "$PGDATA" \
    -Fp -Xs -P -R
  chmod 700 "$PGDATA"
  echo "postgres-primary:5432:replication:replicator:${REPLICATION_PASSWORD:-replica_password}" > /var/lib/postgresql/.pgpass
  chmod 600 /var/lib/postgresql/.pgpass
  echo "[db-standby] Base backup complete; starting in hot-standby mode."
fi
# Keep replication credentials in the persistent data volume so container recreation does not break WAL streaming.
echo "postgres-primary:5432:replication:replicator:${REPLICATION_PASSWORD:-replica_password}" > "$PGDATA/.pgpass"
chmod 600 "$PGDATA/.pgpass"
if ! grep -q "passfile=$PGDATA/.pgpass" "$PGDATA/postgresql.auto.conf" 2>/dev/null; then
  echo "primary_conninfo = 'host=postgres-primary port=5432 user=replicator passfile=$PGDATA/.pgpass application_name=postgres-standby'" >> "$PGDATA/postgresql.auto.conf"
fi
exec /usr/local/bin/docker-entrypoint.sh postgres -c hot_standby=on
