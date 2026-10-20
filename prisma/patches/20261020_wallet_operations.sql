-- Deploy before enabling v2 wallet endpoints. Rerunnable, additive.
-- No cascading FK/expiry: deletion must not make a spent operation reusable.
CREATE TABLE IF NOT EXISTS "WalletOperation" (
  "userId" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "requestHash" TEXT NOT NULL,
  "result" TEXT NOT NULL,
  PRIMARY KEY ("userId", "clientId")
);
