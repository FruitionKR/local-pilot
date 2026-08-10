package fruition.core.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.shared.ai.AiModelCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-models")
public class AiModelCatalogController {
    private final AiModelCatalog catalog;

    public AiModelCatalogController(AiModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<ModelsResponse> list() {
        return ResponseEntity.ok(new ModelsResponse(catalog.enabledModels().stream()
                .map(model -> new ModelResponse(model.provider(), model.model(), model.displayName()))
                .toList()));
    }

    public record ModelsResponse(List<ModelResponse> models) {}
    public record ModelResponse(String provider, String model,
                                @JsonProperty("display_name") String displayName) {}
}
