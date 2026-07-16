package fruition.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayNamesTest {

    @Test
    void resolve_preferredPresent_usesTrimmedPreferred() {
        assertThat(DisplayNames.resolve("  제인  ", "jane.doe@example.com")).isEqualTo("제인");
    }

    @Test
    void resolve_preferredBlank_usesFirstThreeCharsOfEmail() {
        assertThat(DisplayNames.resolve("   ", "jane.doe@example.com")).isEqualTo("jan");
        assertThat(DisplayNames.resolve(null, "jane.doe@example.com")).isEqualTo("jan");
    }

    @Test
    void resolve_shortEmail_usesWholeLocalPartWithinBounds() {
        assertThat(DisplayNames.resolve(null, "ab")).isEqualTo("ab");
    }

    @Test
    void resolve_longPreferred_capsAtMaxLength() {
        String longName = "가".repeat(100);
        String result = DisplayNames.resolve(longName, "x@example.com");
        assertThat(result).hasSize(DisplayNames.MAX_LENGTH);
    }

    @Test
    void isPresent_distinguishesBlankAndNull() {
        assertThat(DisplayNames.isPresent("이름")).isTrue();
        assertThat(DisplayNames.isPresent("  ")).isFalse();
        assertThat(DisplayNames.isPresent(null)).isFalse();
    }
}
