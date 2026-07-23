// 도메인별 API 모듈을 재노출하는 배럴. 기존 `_lib/api` import 경로를 유지한다.
// 새 API 함수는 각 entity 슬라이스(entities/<domain>/api)에 추가한다.
export * from "@/entities/user/api/auth";
export * from "@/entities/workspace/api/workspace";
// getSessionContext는 wiki·export 모듈이 직접 import하는 내부 헬퍼라 배럴에서 제외한다.
export { clearSessionCache, setActiveChatSession, createChatSession, fetchChatSessions, fetchCurrentChatSessionId, fetchChatMessages } from "@/entities/chat/api/chat";
export * from "@/entities/document/api/document";
export * from "./api/note";
export * from "@/entities/wiki/api/wiki";
export * from "./api/agent";
export * from "./api/export";
export * from "@/entities/schema/api/schema";
