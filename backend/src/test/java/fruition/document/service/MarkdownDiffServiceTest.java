package fruition.document.service;

import fruition.document.dto.DocumentContentDiffResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDiffServiceTest {

    private final MarkdownDiffService service = new MarkdownDiffService();

    @Test
    void compare_returnsGitHubStyleLineDiff() {
        DocumentContentDiffResponse response = service.compare(
                "doc_1", 1, "# 제목\n기존 내용\n마지막",
                2, "# 제목\n변경 내용\n추가 내용\n마지막");

        assertThat(response.additions()).isEqualTo(2);
        assertThat(response.deletions()).isEqualTo(1);
        assertThat(response.hunks()).hasSize(1);
        assertThat(response.hunks().getFirst().lines())
                .extracting(DocumentContentDiffResponse.Line::type)
                .containsExactly(
                        DocumentContentDiffResponse.Type.CONTEXT,
                        DocumentContentDiffResponse.Type.DELETE,
                        DocumentContentDiffResponse.Type.ADD,
                        DocumentContentDiffResponse.Type.ADD,
                        DocumentContentDiffResponse.Type.CONTEXT);
        assertThat(response.hunks().getFirst().lines().get(1).oldLine()).isEqualTo(2);
        assertThat(response.hunks().getFirst().lines().get(1).newLine()).isNull();
        assertThat(response.hunks().getFirst().lines().get(2).oldLine()).isNull();
        assertThat(response.hunks().getFirst().lines().get(2).newLine()).isEqualTo(2);
    }

    @Test
    void compare_sameMarkdown_returnsEmptyHunks() {
        DocumentContentDiffResponse response =
                service.compare("doc_1", 1, "동일", 2, "동일");

        assertThat(response.additions()).isZero();
        assertThat(response.deletions()).isZero();
        assertThat(response.hunks()).isEmpty();
    }
}
