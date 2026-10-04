-- Checkpoint values are stored as type-preserving MongoDB Extended JSON (e.g. {"v": {"$oid": "..."}}),
-- which can exceed 255 characters for long string ids or watermarks.
ALTER TABLE checkpoint ALTER COLUMN last_id SET DATA TYPE VARCHAR(2000);
ALTER TABLE checkpoint ALTER COLUMN last_watermark SET DATA TYPE VARCHAR(2000);
