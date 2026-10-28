-- Apply after 20261024_scheduled_creation_operations.sql. Additive/rerunnable.
-- No receipt deletion: both keyed and legacy semantic retry protection remain.
CREATE INDEX IF NOT EXISTS "ScheduledCreationOperation_userId_requestHash_clientId_idx"
ON "ScheduledCreationOperation" ("userId", "requestHash", "clientId");
