# Changelog — Frontend

React 프론트엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-06-12

### feat: 프로젝트와 문서 트리 정리 기능 개선

**변경 배경**

- 프로젝트 이름 변경, 문서 업로드 진입점, 우클릭 폴더 생성, 단일 문서 이동, 문서 병합 정리 등 실제 자료 관리에 필요한 기본 조작이 부족했다.
- 백엔드 Wiki graph에서 생성된 source/concept page도 프론트 트리에서 확인할 수 있어야 했다.
- `processing` 문서를 58% 숫자로 고정 표시하면 실제 진행률처럼 보이므로, 처리 중 상태임을 나타내는 로딩 표시로 바꿀 필요가 있었다.

**추가 및 변경된 내용**

- 프로젝트 우클릭 메뉴에서 프로젝트 이름을 변경할 수 있도록 추가했다.
- 프로젝트의 `+` 버튼은 폴더 생성 대신 문서 업로드 파일 선택을 열도록 변경했다.
- 프로젝트 또는 폴더 내부 우클릭 메뉴에서 새 폴더를 추가할 수 있도록 변경했다.
- 단일 문서 파일도 폴더처럼 드래그 이동할 수 있도록 변경했다.
- 문서 이름 변경 시 polling으로 문서 목록을 다시 받아도 사용자가 변경한 이름이 유지되도록 처리했다.
- 문서 파일을 다른 문서 파일 위에 놓으면 `새 문서 묶음` 폴더를 만들고 두 문서를 그 안으로 넣도록 변경했다.
- 백엔드 Wiki graph의 source/concept node를 `Source 문서`, `Concept 문서` 가상 폴더로 사이드바에 표시하도록 추가했다.
- `processing`, `uploaded`, `uploading` 상태 표시는 숫자나 텍스트 대신 회전 로딩 indicator로 변경했다.
- graph canvas의 processing node도 58% 숫자 표시 대신 회전 링으로 표시하도록 변경했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### feat: 문서 업로드와 Wiki graph 백엔드 연동

**변경 배경**

- 기존 프론트는 프로젝트/폴더 UI와 graph 화면을 정적 목업 데이터로 표시하고 있었다.
- 백엔드에는 문서 업로드, 원본 스토리지 저장, LLM pipeline 처리, Wiki graph 조회 API가 이미 구현되어 있어 프론트만 API 계약에 맞춰 연결해야 했다.
- 프론트 작업 지침에 백엔드, 인프라, AI/LLM pipeline 코드는 수정하지 않고 프론트 코드만 수정한다는 원칙을 명시해야 했다.

**추가 및 변경된 내용**

- `frontend/next.config.mjs`에 `/api/:path*` rewrite를 추가해 프론트 개발 서버에서 Spring backend API를 호출하도록 연결했다.
- `frontend/app/page.tsx`에서 `GET /api/documents`, `GET /api/wiki/graph`, `POST /api/documents`를 사용하도록 변경했다.
- 문서 업로드 후 문서 목록과 Wiki graph를 재조회하고, `processing` 또는 `uploaded` 상태 문서가 있으면 polling하도록 구성했다.
- 백엔드 graph 응답을 캔버스용 `GraphNode`, `GraphLink` 데이터로 변환해 정적 목업 대신 실제 DB 기반 node와 edge를 표시하도록 변경했다.
- OS 파일 드래그 앤 드롭으로 `.pdf`, `.md` 문서를 프로젝트 또는 폴더에 업로드할 수 있도록 구성했다.
- 프로젝트/폴더 트리에서 폴더 추가, 드래그 이동, 중첩, 우클릭 이름 변경/삭제, 파일 드롭 대상 표시를 지원하도록 변경했다.
- 기존 더미 프로젝트/폴더 데이터는 제거하고, 실제 백엔드 문서가 붙는 `업로드 문서` 프로젝트만 초기 표시하도록 정리했다.
- 우측 채팅 사이드바와 workspace 레이아웃에서 화면 폭이 줄어도 가로 스크롤이 생기지 않도록 스타일을 보정했다.
- `frontend/AGENTS.md`, `frontend/CLAUDE.md`에 프론트엔드 작업 시 백엔드/인프라/AI/LLM pipeline 코드를 수정하지 않는다는 지침을 추가했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- 프론트 개발 서버 proxy 기준 `GET /api/documents`, `GET /api/wiki/graph` 응답을 확인했다.
- 프론트 개발 서버 proxy 기준 `POST /api/documents`로 문서 업로드가 백엔드까지 전달되는 것을 확인했다.
- 업로드된 테스트 문서가 `completed`로 전환되고 Wiki graph API에 source/concept node가 추가되는 것을 확인했다.

**주의사항**

- 로컬 DB에는 이전 실패 검증 과정에서 생성된 `processing` 또는 `failed` 상태 문서가 남아 있다.
- `llm-wiki.md`, 일부 `README.md`, `backend.md`는 `pipeline_runs` 실행 기록이 없어 업로드 당시 pipeline 요청이 생성되지 않은 채 `processing`으로 남은 데이터로 확인했다.
- 실패한 `README.md`는 `ERROR: Missing API key. Set UPSTAGE_API_KEY=... or pass --api-key` 사유로 pipeline 실패 상태가 저장되어 있다.

---

## 2026-06-05 이전

### feat: 옵시디언 스타일 그래프 UI 구현 (`95e26ff`)

**추가된 것**

- Wiki 그래프 화면 초기 구현 (React, D3 기반)
- 옵시디언 스타일 node / edge 렌더링

---

*커밋 단위 이력은 `git log` 로 확인하세요.*
