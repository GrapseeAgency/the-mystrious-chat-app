-- Additive/rerunnable. Deploy before enabling native read-state invalidations.
CREATE TABLE IF NOT EXISTS "PushReadStateCapability" (
  "tokenId" TEXT NOT NULL PRIMARY KEY,
  "version" INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY ("tokenId") REFERENCES "PushToken"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
