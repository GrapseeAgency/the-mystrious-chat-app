-- Apply once with the matching composer API; this patch is not run automatically.
ALTER TABLE ConversationParticipant ADD COLUMN draftRevision TEXT NOT NULL DEFAULT '0';
UPDATE ConversationParticipant SET draftRevision='1' WHERE draft IS NOT NULL AND draft<>'';
CREATE TABLE ComposerOperation (
 userId TEXT NOT NULL, clientId TEXT NOT NULL, kind TEXT NOT NULL,
 targetId TEXT NOT NULL, rootId TEXT NOT NULL DEFAULT '', requestHash TEXT NOT NULL,
 revision TEXT NOT NULL, outcome TEXT NOT NULL, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(userId,clientId)
);
CREATE TABLE ThreadDraftState (
 userId TEXT NOT NULL, conversationId TEXT NOT NULL, rootId TEXT NOT NULL,
 draft TEXT NOT NULL, revision TEXT NOT NULL, PRIMARY KEY(userId,conversationId,rootId)
);
