package fruition.core.document.mongo;

import java.time.Instant;

public record MongoDocumentEditSaveResult(
        long baseRevision,
        String baseMarkdown,
        String baseContentHash,
        long revision,
        String contentHash,
        Instant updatedAt,
        String actorUserId,
        boolean changed
) {
}
