package fruition.aihistory.domain;

/**
 * 복구 범위. 기준 작업 하나를 지목하고 이 값으로 제외할 작업 집합을 정한다.
 *
 * <p>lint는 {@code target_document_id}가 없어 {@link #single}만 쓸 수 있다.
 */
public enum RestoreMode {
    /**
     * 이 시점으로 되돌리기. 기준 작업 <b>이후</b> 같은 문서의 작업을 전부 제외한다.
     * 로그 목록에서 한 시점을 골라 되돌리는 것이 가장 흔한 조작이라 기본값이다.
     */
    since,
    /** 이 작업 하나만 취소한다. */
    single,
    /** 같은 문서의 작업을 전부 취소한다. 기준 작업 자신도 포함한다. */
    document;

    public static RestoreMode orDefault(RestoreMode mode) {
        return mode == null ? since : mode;
    }
}
