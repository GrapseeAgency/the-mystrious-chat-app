-- Apply once with the matching upload API; this patch is not run automatically.
CREATE TABLE UploadOperation (
 clientKey TEXT PRIMARY KEY,
 contentHash TEXT NOT NULL,
 name TEXT NOT NULL,
 createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
