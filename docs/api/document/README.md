# document-svc API

[API 문서](../README.md)

문서와 core DB를 소유하고 사용자용 AI·Wiki·Agent 요청을 중계한다. 로컬 base URL은 `http://localhost:8080`이다.

| 도메인 | API 수 | 역할 |
|---|---:|---|
| [AI](ai.md) | 9 | AI 모델 설정과 작업·변환·ingest 관리 |
| [Agent](agent.md) | 10 | Agent 실행·승인과 내부 Tool 호출 |
| [Chat](chat.md) | 6 | 채팅 세션·메시지와 Wiki 내보내기 |
| [Documents](documents/README.md) | 24 | 문서 관리·본문·편집·이력 |
| [Navigation](navigation.md) | 10 | 폴더와 문서 트리 탐색 |
| [Query](query.md) | 4 | 동기·비동기 Query와 SSE |
| [Skills](skills.md) | 8 | Skill 작성·게시·설정과 참조 읽기 |
| [Wiki](wiki.md) | 8 | Wiki 조회·기여·유지보수 |
| [Wiki Schema](wiki-schema.md) | 4 | Wiki 스키마 초안·미리보기·활성화 |
