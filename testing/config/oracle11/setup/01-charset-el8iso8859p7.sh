#!/usr/bin/env bash
# Change the database character set of this brand-new Oracle XE 11g instance
# from AL32UTF8 (which the gvenzl image ships with) to EL8ISO8859P7 (Greek
# single-byte). This is the non-Unicode legacy encoding that several of our
# clients still run on 11g, and is the main reason this test container
# exists.
#
# Background:
#   - gvenzl/oracle-xe:11-slim-faststart uncompresses a pre-built database
#     created in AL32UTF8. The ORACLE_CHARACTERSET env var is NOT honoured
#     by this image family (both xe and free variants ship pre-built CDBs).
#   - Oracle's supported tool for character set migration is `csalter`, but
#     it only permits SUPERSET conversions — AL32UTF8 → EL8ISO8859P7 is a
#     downgrade (Unicode has code points that ISO-8859-7 cannot represent),
#     so csalter refuses.
#   - The `INTERNAL_USE` clause on `ALTER DATABASE CHARACTER SET` bypasses
#     the superset check. This is an Oracle-internal escape hatch normally
#     reserved for Oracle Support, but it is safe on an EMPTY database (no
#     user data that could be misinterpreted under the new charset). This
#     script runs during the container's first-boot init phase, before any
#     tables exist, so the safety condition holds.
#
# This script is placed in /container-entrypoint-initdb.d/ (via a volume
# mount in docker-compose.yml) and runs exactly once — on first container
# start, after gvenzl has brought the DB up and created the stormify user.

set -euo pipefail

echo "oracle11-charset: converting database character set to EL8ISO8859P7"

sqlplus -s / as sysdba <<'SQL'
WHENEVER SQLERROR EXIT SQL.SQLCODE

-- Quiesce the instance: shut down cleanly and come back up in RESTRICTED
-- mode so nothing else can connect while we mutate the catalog.
SHUTDOWN IMMEDIATE;
STARTUP RESTRICT;

-- Disable background jobs and AQ processes — they can hold catalog locks
-- that conflict with ALTER DATABASE CHARACTER SET.
ALTER SYSTEM ENABLE RESTRICTED SESSION;
ALTER SYSTEM SET JOB_QUEUE_PROCESSES=0;
ALTER SYSTEM SET AQ_TM_PROCESSES=0;

-- The actual conversion. INTERNAL_USE skips the superset validation that
-- the standard `ALTER DATABASE CHARACTER SET` statement enforces.
ALTER DATABASE CHARACTER SET INTERNAL_USE EL8ISO8859P7;

-- Bump the process / session ceiling for the test workload. Oracle XE 11g
-- ships with processes=100 / sessions=172, which is easily exhausted when
-- our native test harness opens a fresh connection per statement without
-- pooling and the server is slow to reap PGA slots between tests. The
-- changes are written to the SPFILE (SCOPE=SPFILE) so they apply after
-- the restart below. Keep the ratio roughly processes * 1.5 = sessions.
ALTER SYSTEM SET processes=600 SCOPE=SPFILE;
ALTER SYSTEM SET sessions=900 SCOPE=SPFILE;
ALTER SYSTEM SET transactions=1000 SCOPE=SPFILE;

-- Bounce back to a normal open state so subsequent init scripts (and our
-- application tests) can use the instance.
SHUTDOWN IMMEDIATE;
STARTUP;

-- After a fresh STARTUP, PMON registers services with the listener on its
-- own schedule (every ~60s by default). If the next incoming connection
-- beats that timer it receives ORA-12516 because the listener does not yet
-- know about the XE service. `ALTER SYSTEM REGISTER` forces an immediate
-- registration so subsequent client connections succeed right away.
ALTER SYSTEM REGISTER;

-- Sanity print so the container log records what charset we ended up with.
COL parameter FORMAT A30
COL value FORMAT A20
SELECT parameter, value FROM nls_database_parameters
 WHERE parameter IN ('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET');

EXIT;
SQL

# Wait until the listener reports XE as "READY" before handing control back
# to the gvenzl entrypoint. lsnrctl status lists registered services; we
# poll it briefly to absorb any remaining PMON lag.
for i in 1 2 3 4 5 6 7 8 9 10; do
    if lsnrctl status 2>/dev/null | grep -q 'Service "XE".*has 1 instance.*status READY'; then
        echo "oracle11-charset: listener reports XE READY"
        break
    fi
    echo "oracle11-charset: waiting for listener to report XE READY ($i/10)"
    sleep 1
done

echo "oracle11-charset: conversion complete"
