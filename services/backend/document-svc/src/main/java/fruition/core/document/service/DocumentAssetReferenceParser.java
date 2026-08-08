package fruition.core.document.service;

import fruition.core.document.exception.InvalidDocumentAssetException;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentAssetReferenceParser {

    private static final Pattern MANAGED_ASSET_PATH = Pattern.compile(
            "^/api/workspaces/([^/]+)/assets/"
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})"
                    + "/content$"
    );

    private final Parser parser = Parser.builder().build();

    public Set<ManagedAssetReference> parse(String markdown) {
        Set<ManagedAssetReference> references = new LinkedHashSet<>();
        parser.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String destination = image.getDestination();
                if (destination.startsWith("/api/workspaces/")) {
                    Matcher matcher = MANAGED_ASSET_PATH.matcher(destination);
                    if (!matcher.matches()) {
                        throw new InvalidDocumentAssetException("관리 이미지 경로가 올바르지 않습니다.");
                    }
                    references.add(new ManagedAssetReference(
                            matcher.group(1), UUID.fromString(matcher.group(2))));
                }
                visitChildren(image);
            }
        });
        return Set.copyOf(references);
    }

    public record ManagedAssetReference(String workspaceId, UUID assetId) {}
}
