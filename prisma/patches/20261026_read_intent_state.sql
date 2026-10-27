-- Additive/rerunnable. Deploy before read-intent backend/native clients.
-- Per-membership identity prevents tokens surviving leave/rejoin.
CREATE TABLE IF NOT EXISTS "ReadIntentState" (
  "participantId" TEXT NOT NULL PRIMARY KEY,
  "revision" TEXT NOT NULL,
  FOREIGN KEY ("participantId") REFERENCES "ConversationParticipant"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
