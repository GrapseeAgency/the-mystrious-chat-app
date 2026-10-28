-- Additive indexes for exact window navigation and unbounded keyset traversal.
CREATE INDEX IF NOT EXISTS Message_room_order ON Message(conversationId, createdAt, id);
CREATE INDEX IF NOT EXISTS Message_thread_order ON Message(parentId, createdAt, id);
-- Main-history reads must not scan every reply in the same conversation.
CREATE INDEX IF NOT EXISTS Message_room_thread_order ON Message(conversationId, parentId, createdAt, id);
CREATE INDEX IF NOT EXISTS Message_history_order ON Message(createdAt, id);
CREATE INDEX IF NOT EXISTS SavedMessage_owner_order ON SavedMessage(userId, savedAt, messageId);
