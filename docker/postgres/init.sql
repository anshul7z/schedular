-- Runs once when the postgres volume is first created.
-- schedular_meta (metadata DB) is created by POSTGRES_DB; this adds a separate migration target.
CREATE USER warehouse WITH PASSWORD 'warehouse';
CREATE DATABASE warehouse OWNER warehouse;
