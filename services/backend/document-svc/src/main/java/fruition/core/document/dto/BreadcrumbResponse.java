package fruition.core.document.dto;

import java.util.List;

public record BreadcrumbResponse(List<Node> path) {

    public record Node(String type, String id, String name) {
        public static Node folder(String id, String name) {
            return new Node("folder", id, name);
        }

        public static Node document(String id, String name) {
            return new Node("document", id, name);
        }
    }
}
