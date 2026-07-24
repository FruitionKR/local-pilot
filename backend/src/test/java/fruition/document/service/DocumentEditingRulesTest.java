package fruition.document.service;

import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.exception.InvalidMarkdownContentException;
import fruition.document.exception.MarkdownContentTooLargeException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentEditingRulesTest {

    @Test
    void rename_preservesPdfAndMarkdownExtensions() {
        assertThat(DocumentEditingRules.rename(" 새 보고서 ", "보고서.pdf"))
                .isEqualTo(new DocumentEditingRules.Filename("새 보고서", "새 보고서.pdf", "새 보고서.pdf"));
        assertThat(DocumentEditingRules.rename("회의록", "기존.MD"))
                .isEqualTo(new DocumentEditingRules.Filename("회의록", "회의록.MD", "회의록.md"));
    }

    @Test
    void rename_rejectsInvalidNamesAndFilenameLongerThan255Characters() {
        for (String invalid : new String[]{" ", ".", "..", "a/b", "a\\b", "a\nb", "a\0b"}) {
            assertThatThrownBy(() -> DocumentEditingRules.rename(invalid, "기존.md"))
                    .isInstanceOf(InvalidDocumentFilenameException.class);
        }

        assertThatThrownBy(() -> DocumentEditingRules.rename("가".repeat(253), "기존.md"))
                .isInstanceOf(InvalidDocumentFilenameException.class);
        assertThat(DocumentEditingRules.rename("가".repeat(252), "기존.md").filename()).hasSize(255);
    }

    @Test
    void markdown_allowsEmptyAndRejectsNull() {
        assertThat(DocumentEditingRules.markdown("").markdown()).isEmpty();
        assertThatThrownBy(() -> DocumentEditingRules.markdown((String) null))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void markdown_validatesFiveMegabytesByUtf8Bytes() {
        int koreanCharactersAtLimit = DocumentEditingRules.MAX_MARKDOWN_BYTES / 3;
        String atLimit = "가".repeat(koreanCharactersAtLimit) + "ab";

        assertThat(atLimit.getBytes(StandardCharsets.UTF_8))
                .hasSize(DocumentEditingRules.MAX_MARKDOWN_BYTES);
        assertThat(DocumentEditingRules.markdown(atLimit).bytes())
                .hasSize(DocumentEditingRules.MAX_MARKDOWN_BYTES);
        assertThatThrownBy(() -> DocumentEditingRules.markdown(atLimit + "가"))
                .isInstanceOf(MarkdownContentTooLargeException.class);
    }

    @Test
    void markdown_rejectsMalformedUtf8Bytes() {
        assertThatThrownBy(() -> DocumentEditingRules.markdown(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(InvalidMarkdownContentException.class);
    }

    @Test
    void markdown_calculatesSha256AndDetectsNoOp() {
        DocumentEditingRules.MarkdownContent content = DocumentEditingRules.markdown("hello");

        assertThat(content.contentHash())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(content.hasSameContent(content.contentHash())).isTrue();
        assertThat(content.hasSameContent("different")).isFalse();
    }
}
