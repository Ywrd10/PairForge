-- Deliberately invalid, test-only migration: PostgreSQL must roll back the DDL.
CREATE TABLE partial_migration (id integer);
SELECT deliberately_missing_pairforge_function();
