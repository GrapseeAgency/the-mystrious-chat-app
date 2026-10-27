-- Run after read_intent_state, before deploying exact-read clients/server.
-- One transaction. Re-running must NEVER infer new observations from lastReadAt.
BEGIN IMMEDIATE;
-- Existing manual reminders need server authority in the first cached snapshot.
-- Never rotate an existing revision while migrating/re-running this patch.
INSERT OR IGNORE INTO "ReadIntentState" ("participantId", "revision")
SELECT id, lower(hex(randomblob(16))) FROM "ConversationParticipant";

CREATE TABLE IF NOT EXISTS "MessageReadObservation" (
  "participantId" TEXT NOT NULL, "messageId" TEXT NOT NULL,
  "observedAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "source" TEXT NOT NULL DEFAULT 'visible',
  PRIMARY KEY ("participantId", "messageId"),
  FOREIGN KEY ("participantId") REFERENCES "ConversationParticipant"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  FOREIGN KEY ("messageId") REFERENCES "Message"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "MessageReadObservation_messageId_idx" ON "MessageReadObservation"("messageId");
CREATE TABLE IF NOT EXISTS "ReadLedgerMigration" ("id" TEXT NOT NULL PRIMARY KEY);
-- Preserve legacy badge counts once, but never advertise an inferred legacy
-- timestamp as exact Seen evidence. New-member history is not retroactively Seen.
INSERT OR IGNORE INTO "MessageReadObservation" ("participantId", "messageId", "observedAt", "source")
SELECT p.id, m.id, p.lastReadAt, 'legacy'
FROM ConversationParticipant p JOIN Message m ON m.conversationId = p.conversationId
WHERE m.createdAt <= p.lastReadAt AND m.createdAt >= p.joinedAt
  AND NOT EXISTS (SELECT 1 FROM ReadLedgerMigration WHERE id = 'exact-v1');
INSERT OR IGNORE INTO "ReadLedgerMigration" ("id") VALUES ('exact-v1');
COMMIT;
