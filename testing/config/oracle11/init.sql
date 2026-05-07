-- Oracle XE 11g: APP_USER (stormify) is created by the gvenzl image via the
-- APP_USER / APP_USER_PASSWORD environment variables. The grants below are
-- identical to the 21c configuration in ../oracle/init.sql — kept separate
-- so the two Oracle targets can diverge if we ever need 11g-specific DDL.
GRANT CREATE TABLE TO stormify;
GRANT CREATE SEQUENCE TO stormify;
GRANT UNLIMITED TABLESPACE TO stormify;

-- Sandbox schema for the schema-sync demo runner. Oracle has no per-database
-- isolation in XE, so we use a separate user (= schema) instead.
CREATE USER stormify_demo IDENTIFIED BY "Stormify1!";
GRANT CREATE SESSION TO stormify_demo;
GRANT CREATE TABLE TO stormify_demo;
GRANT CREATE SEQUENCE TO stormify_demo;
GRANT UNLIMITED TABLESPACE TO stormify_demo;
