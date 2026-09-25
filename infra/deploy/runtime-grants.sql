\set ON_ERROR_STOP on
BEGIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON users, rooms, room_members, executions TO pairforge_api;
REVOKE ALL ON flyway_schema_history FROM pairforge_api;
COMMIT;
