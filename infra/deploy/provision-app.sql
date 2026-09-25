\set ON_ERROR_STOP on
\set VERBOSITY terse
\getenv api_password API_DB_PASSWORD
\getenv migration_password MIGRATION_PASSWORD
BEGIN;
-- First-time provisioning only: existing roles cause a rollback, never overwrite.
CREATE ROLE pairforge_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE pairforge_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT format('ALTER ROLE pairforge_api PASSWORD %L', :'api_password') \gexec
SELECT format('ALTER ROLE pairforge_migrator PASSWORD %L', :'migration_password') \gexec
REVOKE CREATE, TEMPORARY ON DATABASE pairforge FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE pairforge TO pairforge_api, pairforge_migrator;
GRANT USAGE ON SCHEMA public TO pairforge_api;
GRANT USAGE, CREATE ON SCHEMA public TO pairforge_migrator;
-- Allows startup validation immediately after Flyway. Runtime writes are granted
-- explicitly by runtime-grants.sql before the public proxy is started.
ALTER DEFAULT PRIVILEGES FOR ROLE pairforge_migrator IN SCHEMA public GRANT SELECT ON TABLES TO pairforge_api;
COMMIT;
