-- 20261031_push_registry_revision.sql
-- Section 6 hardening (notifications/push):
--   1. PushToken.revision  - monotonic rebind counter; an in-flight provider
--      prune must match the snapshotted revision, closing the same-millisecond
--      rebind boundary that `updatedAt` alone cannot order.
--   2. PushToken.origin    - gateway origin the registration was created
--      against; carried in every push payload so native clients can drop
--      banners from a foreign deployment that reuses ids. Null = legacy row.
--   3. PushToken.apnsEnvironment - per-token APNs environment routing
--      ('sandbox'|'production'); null = deployment's PULSE_APNS_ENVIRONMENT.
--   4. LivePushOutbox/LivePushReceipt - durable retry for failed LIVE push
--      fanouts. The drain re-plans with current mute/block/membership policy.
ALTER TABLE "PushToken" ADD COLUMN "revision" INTEGER NOT NULL DEFAULT 1;
ALTER TABLE "PushToken" ADD COLUMN "origin" TEXT;
ALTER TABLE "PushToken" ADD COLUMN "apnsEnvironment" TEXT;
CREATE TABLE "LivePushOutbox" (
  "id" TEXT PRIMARY KEY NOT NULL,
  "messageId" TEXT NOT NULL,
  "createdAtMs" BIGINT NOT NULL,
  "nextAttemptMs" BIGINT NOT NULL,
  "attempts" INTEGER NOT NULL DEFAULT 0,
  "leaseToken" TEXT,
  "leaseUntilMs" BIGINT,
  "completedAtMs" BIGINT
);
CREATE INDEX "LivePushOutbox_ready" ON "LivePushOutbox"("completedAtMs", "nextAttemptMs", "id");
CREATE TABLE "LivePushReceipt" (
  "messageId" TEXT NOT NULL,
  "bindingHash" TEXT NOT NULL,
  "createdAtMs" BIGINT NOT NULL,
  CONSTRAINT "LivePushReceipt_pkey" PRIMARY KEY ("messageId", "bindingHash")
);
