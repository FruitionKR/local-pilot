package fruition.core.document.dto;

import java.util.List;

public record HierarchySearchResponse(List<Match> results) {

    public record Match(
            String type,
            String id,
            String name,
            List<BreadcrumbResponse.Node> breadcrumb
    ) {
    }
}
