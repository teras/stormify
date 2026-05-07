-- MSSQL init: create databases only, tests use sa user
CREATE DATABASE stormify_test;
GO
-- Sandbox database for the schema-sync demo runner so it does not pile
-- mockup tables on top of the conformance suite's stormify_test.
CREATE DATABASE stormify_demo;
GO
