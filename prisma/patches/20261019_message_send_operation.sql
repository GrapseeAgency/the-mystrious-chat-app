-- Additive, rerunnable SQLite patch. Back up the database before deployment.
-- Deliberately no cascading FK: a deleted/expired message must not release its operation key.
CREATE TABLE IF NOT EXISTS "MessageSendOperation" (
  "senderId" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "conversationId" TEXT NOT NULL,
  "requestHash" TEXT NOT NULL,
  "messageId" TEXT NOT NULL,
  PRIMARY KEY ("senderId", "clientId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "MessageSendOperation_messageId_key" ON "MessageSendOperation"("messageId");
