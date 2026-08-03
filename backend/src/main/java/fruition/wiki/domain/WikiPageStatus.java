package fruition.wiki.domain;

public enum WikiPageStatus {
    draft,
    active,
    failed,
    /** 복구로 받치는 기여가 모두 사라진 상태. 이력을 남기려고 하드 삭제하지 않는다. */
    /**
     * Backend는 이 값을 쓰지 않는다. 삭제 여부는 {@code wiki_page_contributions}에 활성 기여가
     * 남았는지로 판단하며, {@code wiki_pages}는 llmPipeline 소유라 건드리지 않는다.
     * DB CHECK 제약(V17)이 이 값을 허용하므로 읽을 수 있도록 남겨 둔다.
     */
    deleted
}
