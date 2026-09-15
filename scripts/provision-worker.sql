\set ON_ERROR_STOP on
\set VERBOSITY terse
\getenv worker_user WORKER_DB_USER
\getenv worker_password WORKER_DB_PASSWORD
SELECT current_database() AS database_name \gset
BEGIN;

SELECT EXISTS (
    SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
    WHERE r.rolname = :'worker_user'
) OR EXISTS (
    SELECT FROM pg_shdepend d JOIN pg_roles r ON r.oid = d.refobjid
    WHERE r.rolname = :'worker_user' AND d.refclassid = 'pg_authid'::regclass
      AND d.deptype = 'o'
) AS worker_has_unexpected_privileges \gset
\if :worker_has_unexpected_privileges
DO $$ BEGIN
    RAISE EXCEPTION 'Refusing worker role with existing role memberships or object ownership';
END $$;
\endif

SELECT format('CREATE ROLE %I LOGIN', :'worker_user')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'worker_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L',
              :'worker_user', :'worker_password') \gexec
REVOKE CREATE, TEMPORARY ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL ON DATABASE :"database_name" FROM :"worker_user";
GRANT CONNECT ON DATABASE :"database_name" TO :"worker_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM :"worker_user";
GRANT USAGE ON SCHEMA public TO :"worker_user";
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM :"worker_user";
-- Table-level REVOKE does not remove separately granted column privileges.
SELECT format('REVOKE ALL (%s) ON TABLE %I.%I FROM %I',
              string_agg(format('%I', column_name), ', ' ORDER BY ordinal_position),
              table_schema, table_name, :'worker_user')
FROM information_schema.columns WHERE table_schema = 'public'
GROUP BY table_schema, table_name \gexec
COMMIT;
-- Milestone 1 requires health connectivity only. Execution privileges arrive
-- with the worker repository, and must never include users or room membership.
