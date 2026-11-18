#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
  CREATE ROLE app WITH LOGIN PASSWORD 'app_password';
  CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replica_password';
  CREATE DATABASE auth_db OWNER app;
  CREATE DATABASE course_db OWNER app;
  CREATE DATABASE grade_db OWNER app;
  CREATE DATABASE enrollment_shard_0 OWNER app;
  CREATE DATABASE enrollment_shard_1 OWNER app;
EOSQL
# Replication connections use a separate pg_hba rule. This file is copied to the standby.
echo "host replication replicator 0.0.0.0/0 scram-sha-256" >> "$PGDATA/pg_hba.conf"
