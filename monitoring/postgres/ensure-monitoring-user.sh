#!/bin/sh
set -e

if [ -z "${POSTGRES_MONITOR_PASSWORD:-}" ]; then
  exit 0
fi

MONITOR_USER="${POSTGRES_MONITOR_USER:-orynx_monitor}"
MARKER_FILE="/tmp/.orynx_monitor_user_initialized"

if [ -f "${MARKER_FILE}" ]; then
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname "${POSTGRES_DB:-orynx}" \
  -v monitor_user="${MONITOR_USER}" \
  -v monitor_password="${POSTGRES_MONITOR_PASSWORD}" <<'EOSQL'
SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'monitor_user', :'monitor_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'monitor_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'monitor_user', :'monitor_password') \gexec
SELECT format('GRANT pg_monitor TO %I', :'monitor_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'monitor_user') \gexec
EOSQL

touch "${MARKER_FILE}"
