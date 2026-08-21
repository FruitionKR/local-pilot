// Markdown 변환 시작 이벤트 버스.
// 변환 트리거 경로(사이드바 메뉴·그래프 위키 반영·문서 화면 위키 반영)가 여러 곳이라,
// API 성공 시점에 한 번 발행하고 화면(HomeWorkspace)이 구독해 완료 시 자동으로 연다.
type ConvertStartedListener = (documentId: string) => void;

const listeners = new Set<ConvertStartedListener>();

export function publishConvertStarted(documentId: string) {
  listeners.forEach((listener) => listener(documentId));
}

export function subscribeConvertStarted(listener: ConvertStartedListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
