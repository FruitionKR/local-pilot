package fruition.document.service;

import fruition.document.dto.DocumentContentDiffResponse;
import fruition.document.exception.MarkdownDiffTooLargeException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class MarkdownDiffService {

    private static final int CONTEXT_LINES = 3;
    private static final long MAX_TRACE_BYTES = 16L * 1024 * 1024;

    public DocumentContentDiffResponse compare(
            String documentId, long fromVersion, String before, long toVersion, String after) {
        if (before.equals(after)) {
            return new DocumentContentDiffResponse(
                    documentId, fromVersion, toVersion, 0, 0, List.of());
        }
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        List<Edit> edits = myers(oldLines, newLines);
        List<NumberedLine> numbered = number(edits);
        List<DocumentContentDiffResponse.Hunk> hunks = hunks(numbered);
        int additions = (int) edits.stream().filter(edit -> edit.type == Type.ADD).count();
        int deletions = (int) edits.stream().filter(edit -> edit.type == Type.DELETE).count();
        return new DocumentContentDiffResponse(
                documentId, fromVersion, toVersion, additions, deletions, hunks);
    }

    private List<String> lines(String markdown) {
        if (markdown.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(markdown.split("\\R", -1));
    }

    private List<Edit> myers(List<String> before, List<String> after) {
        int n = before.size();
        int m = after.size();
        int max = n + m;
        int offset = max;
        long frontierLength = 2L * max + 1;
        long frontierBytes = frontierLength * Integer.BYTES;
        if (frontierBytes * 2 > MAX_TRACE_BYTES) {
            throw diffTooLarge();
        }
        int[] frontier = new int[(int) frontierLength];
        List<int[]> trace = new ArrayList<>();

        for (int distance = 0; distance <= max; distance++) {
            long estimatedBytes = (trace.size() + 2L) * frontierBytes;
            if (estimatedBytes > MAX_TRACE_BYTES) {
                throw diffTooLarge();
            }
            trace.add(frontier.clone());
            for (int diagonal = -distance; diagonal <= distance; diagonal += 2) {
                int index = offset + diagonal;
                int x;
                if (diagonal == -distance
                        || (diagonal != distance && frontier[index - 1] < frontier[index + 1])) {
                    x = frontier[index + 1];
                } else {
                    x = frontier[index - 1] + 1;
                }
                int y = x - diagonal;
                while (x < n && y < m && before.get(x).equals(after.get(y))) {
                    x++;
                    y++;
                }
                frontier[index] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, before, after, distance, offset);
                }
            }
        }
        throw new IllegalStateException("Markdown diff를 계산할 수 없습니다.");
    }

    private MarkdownDiffTooLargeException diffTooLarge() {
        return new MarkdownDiffTooLargeException(
                "두 문서의 차이가 너무 커서 안전하게 비교할 수 없습니다.");
    }

    private List<Edit> backtrack(
            List<int[]> trace, List<String> before, List<String> after, int distance, int offset) {
        int x = before.size();
        int y = after.size();
        List<Edit> reversed = new ArrayList<>();

        for (int d = distance; d > 0; d--) {
            int[] previous = trace.get(d);
            int diagonal = x - y;
            int previousDiagonal;
            if (diagonal == -d
                    || (diagonal != d
                    && previous[offset + diagonal - 1] < previous[offset + diagonal + 1])) {
                previousDiagonal = diagonal + 1;
            } else {
                previousDiagonal = diagonal - 1;
            }
            int previousX = previous[offset + previousDiagonal];
            int previousY = previousX - previousDiagonal;
            while (x > previousX && y > previousY) {
                reversed.add(new Edit(Type.CONTEXT, before.get(x - 1)));
                x--;
                y--;
            }
            if (x == previousX) {
                reversed.add(new Edit(Type.ADD, after.get(y - 1)));
                y--;
            } else {
                reversed.add(new Edit(Type.DELETE, before.get(x - 1)));
                x--;
            }
        }
        while (x > 0 && y > 0) {
            reversed.add(new Edit(Type.CONTEXT, before.get(x - 1)));
            x--;
            y--;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private List<NumberedLine> number(List<Edit> edits) {
        int oldLine = 1;
        int newLine = 1;
        List<NumberedLine> result = new ArrayList<>();
        for (Edit edit : edits) {
            Integer oldNumber = edit.type == Type.ADD ? null : oldLine++;
            Integer newNumber = edit.type == Type.DELETE ? null : newLine++;
            result.add(new NumberedLine(edit.type, oldNumber, newNumber, edit.content));
        }
        return result;
    }

    private List<DocumentContentDiffResponse.Hunk> hunks(List<NumberedLine> lines) {
        List<DocumentContentDiffResponse.Hunk> result = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            while (index < lines.size() && lines.get(index).type == Type.CONTEXT) {
                index++;
            }
            if (index == lines.size()) {
                break;
            }
            int start = Math.max(0, index - CONTEXT_LINES);
            int lastChange = index;
            int cursor = index + 1;
            while (cursor < lines.size()) {
                if (lines.get(cursor).type != Type.CONTEXT) {
                    lastChange = cursor;
                } else if (cursor - lastChange > CONTEXT_LINES * 2) {
                    break;
                }
                cursor++;
            }
            int end = Math.min(lines.size(), lastChange + CONTEXT_LINES + 1);
            result.add(toHunk(lines.subList(start, end)));
            index = end;
        }
        return result;
    }

    private DocumentContentDiffResponse.Hunk toHunk(List<NumberedLine> lines) {
        int oldStart = firstNumber(lines, true);
        int newStart = firstNumber(lines, false);
        int oldCount = (int) lines.stream().filter(line -> line.type != Type.ADD).count();
        int newCount = (int) lines.stream().filter(line -> line.type != Type.DELETE).count();
        List<DocumentContentDiffResponse.Line> responseLines = lines.stream()
                .map(line -> new DocumentContentDiffResponse.Line(
                        DocumentContentDiffResponse.Type.valueOf(line.type.name()),
                        line.oldLine, line.newLine, line.content))
                .toList();
        return new DocumentContentDiffResponse.Hunk(
                oldStart, oldCount, newStart, newCount, responseLines);
    }

    private int firstNumber(List<NumberedLine> lines, boolean old) {
        return lines.stream()
                .map(line -> old ? line.oldLine : line.newLine)
                .filter(number -> number != null)
                .findFirst()
                .orElse(0);
    }

    private enum Type {
        CONTEXT,
        DELETE,
        ADD
    }

    private record Edit(Type type, String content) {}

    private record NumberedLine(Type type, Integer oldLine, Integer newLine, String content) {}
}
