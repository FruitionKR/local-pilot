// 질의/채팅 응답이 공유하는 관련 페이지 공통 필드.
// wiki·chat 타입 모듈이 내부적으로 참조하며, `_lib/types` 배럴에는 노출하지 않는다.
export type RelatedPageBase = {
  page_type: string;
  title: string;
  slug: string;
  relevance_score: number;
  role: string;
  depth: number;
};
