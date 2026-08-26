-- One database per module, one role per database (ADR-0109, T-9.9).
--
-- WHY PER MODULE AND NOT PER PROCESS
--
-- The process count is still open: identity, catalog and assessment may start
-- inside one `core` process. The DATA boundary is not open. A module that can
-- read another module's tables has no boundary at all, and "we will separate
-- the schemas later" is the version of this that never happens -- by the time
-- anyone tries, a dozen queries join across them.
--
-- So the separation is done here, on day one, where it costs nothing. A merged
-- process holds three datasources. A split later moves no data.
--
-- The enforcement is CREDENTIALS, not convention: each role can see its own
-- database and nothing else. A cross-module query does not return the wrong
-- answer, it fails to connect -- which is the only kind of boundary that
-- survives a deadline.

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- Keycloak's own database. Not a module; it belongs to Keycloak.
-- ---------------------------------------------------------------------------
CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;

-- ---------------------------------------------------------------------------
-- The modules.
--
-- Passwords here are development values and are meant to be boring. Nothing in
-- this file may be copied into any environment that holds real data.
-- ---------------------------------------------------------------------------

CREATE ROLE identity   WITH LOGIN PASSWORD 'identity';
CREATE ROLE catalog    WITH LOGIN PASSWORD 'catalog';
CREATE ROLE assessment WITH LOGIN PASSWORD 'assessment';
CREATE ROLE streaming  WITH LOGIN PASSWORD 'streaming';
CREATE ROLE reporting  WITH LOGIN PASSWORD 'reporting';

CREATE DATABASE identity   OWNER identity;
CREATE DATABASE catalog    OWNER catalog;
CREATE DATABASE assessment OWNER assessment;
CREATE DATABASE streaming  OWNER streaming;
CREATE DATABASE reporting  OWNER reporting;

-- ---------------------------------------------------------------------------
-- Revoke the public schema default.
--
-- Postgres 15 and later already stop non-owners creating in `public`, but
-- CONNECT is still granted to PUBLIC on every new database. Without these,
-- every module role can connect to every other module's database -- which is
-- exactly the boundary this file exists to draw.
-- ---------------------------------------------------------------------------

REVOKE CONNECT ON DATABASE identity   FROM PUBLIC;
REVOKE CONNECT ON DATABASE catalog    FROM PUBLIC;
REVOKE CONNECT ON DATABASE assessment FROM PUBLIC;
REVOKE CONNECT ON DATABASE streaming  FROM PUBLIC;
REVOKE CONNECT ON DATABASE reporting  FROM PUBLIC;
REVOKE CONNECT ON DATABASE keycloak   FROM PUBLIC;

GRANT CONNECT ON DATABASE identity   TO identity;
GRANT CONNECT ON DATABASE catalog    TO catalog;
GRANT CONNECT ON DATABASE assessment TO assessment;
GRANT CONNECT ON DATABASE streaming  TO streaming;
GRANT CONNECT ON DATABASE reporting  TO reporting;
GRANT CONNECT ON DATABASE keycloak   TO keycloak;
