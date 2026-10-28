-- Apply before deploying v2 native saved intents. Additive; retain tombstones.
CREATE TABLE IF NOT EXISTS SavedMessageState (
  userId TEXT NOT NULL, messageId TEXT NOT NULL, revision TEXT NOT NULL,
  PRIMARY KEY(userId,messageId)
);
CREATE TABLE IF NOT EXISTS SavedMessageOperation (
  userId TEXT NOT NULL, clientId TEXT NOT NULL, messageId TEXT NOT NULL,
  requestHash TEXT NOT NULL, revision TEXT NOT NULL, outcome TEXT NOT NULL,
  PRIMARY KEY(userId,clientId)
);
