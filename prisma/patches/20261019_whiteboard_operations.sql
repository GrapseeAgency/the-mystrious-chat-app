-- Apply before deploying the replay-aware whiteboard endpoint. Rerunnable.
-- No cascading FK: Undo/Clear must retain operation receipts.
CREATE TABLE IF NOT EXISTS "WhiteboardStrokeOperation" (
  "senderId" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "requestHash" TEXT NOT NULL,
  "strokeId" TEXT NOT NULL,
  PRIMARY KEY ("senderId", "clientId")
);
CREATE UNIQUE INDEX IF NOT EXISTS "WhiteboardStrokeOperation_strokeId_key" ON "WhiteboardStrokeOperation"("strokeId");
