package fruition.wiki.domain;

public enum WikiPageStatus {
    draft,
    active,
    failed,
    /** 복구로 받치는 기여가 모두 사라진 상태. 이력을 남기려고 하드 삭제하지 않는다. */
    deleted
}
