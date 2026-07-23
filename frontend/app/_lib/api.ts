// 도메인별 API 모듈을 재노출하는 배럴. 기존 `_lib/api` import 경로를 유지한다.
// 새 API 함수는 각 도메인 파일(api/<domain>.ts)에 추가한다.
export * from "./api/auth";
export * from "./api/workspace";
// getSessionContext는 wiki·export 모듈이 직접 import하는 내부 헬퍼라 배럴에서 제외한다.
export { clearSessionCache, fetchChatSessions, fetchCurrentChatSessionId, fetchChatMessages } from "./api/chat";
export * from "./api/document";
export * from "./api/note";
export * from "./api/wiki";
export * from "./api/agent";
export * from "./api/export";
