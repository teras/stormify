-- MySQL: stormify_test + stormify user created via environment variables.
-- Sandbox database for the schema-sync demo runner so it does not pile
-- mockup tables on top of the conformance suite's stormify_test.
CREATE DATABASE stormify_demo;
GRANT ALL PRIVILEGES ON stormify_demo.* TO 'stormify'@'%';
