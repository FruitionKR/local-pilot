package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DocumentContentDiffResponse(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("from_version") long fromVersion,
        @JsonProperty("to_version") long toVersion,
        int additions,
        int deletions,
        List<Hunk> hunks
) {
    public record Hunk(
            @JsonProperty("old_start") int oldStart,
            @JsonProperty("old_lines") int oldLines,
            @JsonProperty("new_start") int newStart,
            @JsonProperty("new_lines") int newLines,
            List<Line> lines
    ) {}

    public record Line(
            Type type,
            @JsonProperty("old_line") Integer oldLine,
            @JsonProperty("new_line") Integer newLine,
            String content
    ) {}

    public enum Type {
        CONTEXT,
        DELETE,
        ADD
    }
}
