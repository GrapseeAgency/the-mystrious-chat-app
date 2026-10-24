-- Apply before releasing native scheduled sends with clientId. Additive/rerunnable.
-- No cascading FK: cancellation, dispatch and room deletion must not release retry identity.
CREATE TABLE IF NOT EXISTS "ScheduledCreationOperation" (
  "userId" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "requestHash" TEXT NOT NULL,
  "scheduledId" TEXT NOT NULL,
  PRIMARY KEY ("userId", "clientId")
);
