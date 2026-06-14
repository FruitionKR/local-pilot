# 프론트엔드 작업 지침

## 작업 범위

- 이 디렉터리에서는 프론트엔드 코드만 수정한다.
- 전체 프로젝트 완성은 프론트엔드 코드 변경으로 달성한다.
- 백엔드, 인프라, AI/LLM pipeline 코드는 직접 수정하지 않는다.

## 백엔드 및 AI 연동 원칙

- 백엔드 API 계약에 맞춰 프론트엔드 요청, 응답 처리, 상태 관리, 화면 표시만 수정한다.
- 백엔드 Java/Spring 코드, DB schema, storage 처리, API endpoint 구현은 수정하지 않는다.
- `llmPipeline` 및 AI/LLM 처리 로직, prompt, pipeline API 코드는 수정하지 않는다.
- 백엔드 또는 AI pipeline 변경이 필요해 보이면, 프론트엔드에서 맞출 수 있는 대안을 먼저 찾고 사용자에게 제약을 설명한다.

## 허용되는 변경

- `frontend/app`, `frontend/svg`, `frontend/package.json`, `frontend/next.config.mjs` 등 프론트엔드 실행과 화면 구현에 필요한 파일 수정
- 백엔드 API 호출을 위한 프론트엔드 fetch/client 코드 작성
- 백엔드 응답을 화면 상태와 UI 컴포넌트에 맞게 변환하는 코드 작성
- 프론트엔드 레이아웃, 스타일, 인터랙션, 접근성 개선

