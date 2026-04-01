-- MSSQL init: create database and user
-- This script runs after the server is ready
CREATE DATABASE stormify_test;
GO
USE stormify_test;
GO
CREATE LOGIN stormify WITH PASSWORD = 'Stormify1!';
GO
CREATE USER stormify FOR LOGIN stormify;
GO
ALTER ROLE db_owner ADD MEMBER stormify;
GO
