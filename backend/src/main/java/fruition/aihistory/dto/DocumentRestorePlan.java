package fruition.aihistory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 문서 되돌리기 계획. 문서 버전은 선형이라 "어느 버전으로 갈지" 하나면 끝난다.
 *
 * @param fromVersion 지금 버전. 미리보기 이후 바뀌면 실행을 거절하는 기준이다
 * @param toVersion   되돌릴 버전. 대상 작업이 손대기 직전 버전이다
 */
public record DocumentRestorePlan(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("from_version") long fromVersion,
        @JsonProperty("to_version") long toVersion
) {}
