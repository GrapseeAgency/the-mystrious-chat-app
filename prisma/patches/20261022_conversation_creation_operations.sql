-- Apply before releasing native group/channel creation with clientId.
-- Additive and rerunnable. Back up the live database before deployment.
-- No cascading FK or expiry: deletion must not make an old create reusable.
CREATE TABLE IF NOT EXISTS "ConversationCreationOperation" (
  "userId" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "requestHash" TEXT NOT NULL,
  "conversationId" TEXT NOT NULL,
  PRIMARY KEY ("userId", "clientId")
);
