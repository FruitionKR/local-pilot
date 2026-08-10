package fruition.shared.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiModelCatalogTest {
    @Test
    void enabledModels_returnsOnlyEnabledProviders() {
        AiModelCatalog catalog = new AiModelCatalog("openai,gemini");

        assertThat(catalog.enabledModels()).extracting(AiModelCatalog.AiModel::provider)
                .containsOnly("openai", "gemini");
    }

    @Test
    void resolve_omittedSelection_usesCurrentDefault() {
        AiModelCatalog catalog = new AiModelCatalog("openai");

        assertThat(catalog.resolve(null, null))
                .isEqualTo(new AiModelCatalog.AiModel("openai", "gpt-4.1-mini", "GPT-4.1 mini"));
    }

    @Test
    void resolve_rejectsPartialOrDisabledSelection() {
        AiModelCatalog catalog = new AiModelCatalog("openai");

        assertThatThrownBy(() -> catalog.resolve("openai", null))
                .isInstanceOf(InvalidAiModelException.class);
        assertThatThrownBy(() -> catalog.resolve("claude", "claude-sonnet-5"))
                .isInstanceOf(InvalidAiModelException.class);
    }
}
