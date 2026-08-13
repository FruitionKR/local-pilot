package fruition.core.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 문서 편집 revision 되돌리기 계획. 편집 revision은 선형이라 "어느 revision으로 갈지" 하나면 끝난다.
 *
 * @param fromVersion 현재 편집 revision. 미리보기 이후 바뀌면 실행을 거절하는 기준이다
 * @param toVersion   되돌릴 편집 revision. 대상 작업이 손대기 직전 revision이다
 */
public record DocumentRestorePlan(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("from_version") long fromVersion,
        @JsonProperty("to_version") long toVersion
) {}
