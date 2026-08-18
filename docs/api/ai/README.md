# ai-svc API

[API 문서](../README.md)

Query·Agent·Wiki·Skill pipeline의 내부 API다. 로컬 base URL은 `http://localhost:8000`이다.

| 도메인 | API 수 | 역할 |
|---|---:|---|
| [Agent](agent.md) | 7 | Agent turn·run·artifact·Tool 인가 |
| [Pipeline](pipeline.md) | 7 | pipeline 실행·상태·로그와 상태 점검 |
| [Query](query.md) | 1 | Wiki 기반 질의 실행 |
| [Skills](skills.md) | 8 | Skill 조회·작성·게시·설정 |
| [Wiki](wiki.md) | 10 | Wiki 조회·ingest·lint·복구 |
| [Wiki Schema](wiki-schema.md) | 4 | Wiki 스키마 조회·초안·미리보기·활성화 |
