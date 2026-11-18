#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "Promoting the PostgreSQL standby. This is a manual, single-primary failover procedure."
docker compose exec postgres-standby pg_ctl -D /var/lib/postgresql/data promote
sleep 3
echo "Stopping the old primary to avoid two writable primaries."
docker compose stop postgres-primary
if [ ! -f .env ]; then cp .env.example .env; fi
if grep -q '^DB_HOST=' .env; then sed -i 's/^DB_HOST=.*/DB_HOST=postgres-standby/' .env; else echo 'DB_HOST=postgres-standby' >> .env; fi
echo "Restarting backend containers against postgres-standby."
docker compose up -d --no-deps auth-service course-service enrollment-service grade-service
echo "Failover complete. Check: docker compose ps && docker compose logs --tail=80 auth-service course-service enrollment-service grade-service"
echo "The old primary is stopped. Do not start it again as writable without rebuilding/re-seeding it from the new primary."
