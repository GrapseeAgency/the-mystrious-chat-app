-- Apply before the matching dispatch route. Metadata only — never copy message bodies.
CREATE TABLE ScheduledDeliveryNotice (
 messageId TEXT PRIMARY KEY NOT NULL, createdAtMs BIGINT NOT NULL,
 nextAttemptMs BIGINT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 leaseToken TEXT, leaseUntilMs BIGINT, socketDone BOOLEAN NOT NULL DEFAULT false,
 pushDone BOOLEAN NOT NULL DEFAULT false, pushCursor TEXT, completedAtMs BIGINT
);
CREATE INDEX ScheduledDeliveryNotice_ready ON ScheduledDeliveryNotice(completedAtMs,nextAttemptMs,messageId);
CREATE TABLE ScheduledPushReceipt (
 messageId TEXT NOT NULL, bindingHash TEXT NOT NULL,
 PRIMARY KEY(messageId,bindingHash)
);
