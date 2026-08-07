package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record FolderChildrenResponse(List<Item> items) {

    public record Item(
            String type,
            String id,
            String name,
            @JsonProperty("sort_order") long sortOrder,
            @JsonProperty("current_version") long currentVersion,
            @JsonProperty("has_children") boolean hasChildren
    ) {
        public static Item folder(
                String id,
                String name,
                long sortOrder,
                long currentVersion,
                boolean hasChildren
        ) {
            return new Item("folder", id, name, sortOrder, currentVersion, hasChildren);
        }

        public static Item document(String id, String name, long sortOrder, long currentVersion) {
            return new Item("document", id, name, sortOrder, currentVersion, false);
        }
    }
}
