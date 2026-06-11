package fruition.wiki.dto;

import java.util.List;

public record WikiGraphResponse(
        List<WikiGraphNode> nodes,
        List<WikiGraphEdge> edges
) {}
