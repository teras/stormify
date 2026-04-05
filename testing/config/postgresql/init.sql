-- PostgreSQL: the default "stormify_test" database and the "stormify" user
-- are created via the POSTGRES_DB / POSTGRES_USER environment variables in
-- docker-compose.yml. This script adds the extra per-encoding databases the
-- encoding tests use to verify that the native driver transcodes correctly
-- between the server's storage encoding and the client's UTF-8 setting.
--
-- Each database uses LC_COLLATE 'C' / LC_CTYPE 'C' so that the locale does
-- not have to support the target encoding (many containers only ship
-- en_US.utf8). template0 is required because template1 is itself UTF-8 and
-- cannot be copied into a differently-encoded database.

CREATE DATABASE stormify_enc_latin1
    ENCODING 'LATIN1'     LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;

CREATE DATABASE stormify_enc_iso7
    ENCODING 'ISO_8859_7' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;

CREATE DATABASE stormify_enc_utf8
    ENCODING 'UTF8'       LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0
    OWNER stormify;
