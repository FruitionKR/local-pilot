# Changelog — Frontend

React 프론트엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-06-16

### feat: Figma 다크 워크스페이스 화면 반영

**변경 배경**

- Figma `v1`의 홈 그래프 화면과 원본문서 패널이 열린 화면을 기준으로 현재 프론트 화면의 테마, 패널 배치, 그래프/Agent UI를 맞출 필요가 있었다.
- SVG 신규 추출은 후속 작업으로 미루고, 현재 코드와 기존 SVG 자산만으로 수정 가능한 화면 요소를 먼저 반영했다.

**변경된 내용**

- topbar, 좌측 rail, 자료 관리 sidebar, graph canvas, filter chip, Agent panel을 Figma 기준의 다크 테마로 조정했다.
- 좌측 트리 항목 선택 시 Figma의 원본문서 패널 상태처럼 graph 왼쪽에 원본문서 미리보기 패널이 열리도록 추가했다.
- graph canvas 내부 node label, edge, raw/source/progress 색상을 다크 배경에 맞게 보정했다.
- 새 `arrow.svg`를 프로젝트/폴더 접기 아이콘에 적용하고, 변경된 `Frame.svg`가 채팅 전송 버튼에 반영되도록 조정했다.
- 문서 추가 버튼은 프로젝트 제목 영역에 hover 또는 focus가 있을 때만 `switch.svg`로 표시되도록 변경했다.
- 문서 추가 `switch.svg`를 `자료 관리` 헤더 영역으로 옮기고, 헤더 hover 또는 focus 시에만 표시되도록 변경했다.
- `자료 관리` 헤더와 프로젝트 목록 사이 divider를 추가하고, 학교 선택 토글을 새 `toggle.svg`로 교체했다.
- Figma 1920x1080 frame 기준으로 topbar 높이, sidebar divider/list offset, 원본문서 패널 폭과 padding, graph/chip 배치를 보정했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

## 2026-06-12

### refactor: 프론트 미사용 목업 자산 정리

**변경 배경**

- 백엔드 API 기반 graph로 전환된 뒤에도 이전 정적 graph 목업 데이터와 계산 helper가 `frontend/app/page.tsx`에 남아 있었다.
- 사용되지 않는 `mvp main.png` 시안 이미지가 프론트 디렉터리에 남아 있었다.
- `.gitignore`에서 `.github/` 전체를 무시해 추후 workflow 추가가 누락될 수 있었다.

**변경된 내용**

- `frontend/app/page.tsx`의 미사용 정적 graph 목업 node/link 데이터와 전용 전역 계산 helper를 제거했다.
- 참조되지 않는 `frontend/mvp main.png` 파일을 삭제했다.
- `.gitignore`에서 `.github/` ignore 규칙을 제거했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### feat: 문서 선택 유지와 rename API 계약 정리

**변경 배경**

- 왼쪽 트리에서 문서를 클릭한 뒤 마우스를 graph 위로 이동하면 선택 효과가 사라져, 사용자가 어떤 문서를 확인 중인지 유지되지 않았다.
- 문서 이름 변경은 프론트 메모리 상태에만 적용되어 새로고침 또는 API 재조회 후 기존 이름으로 되돌아갔다.
- 실제 영속 rename을 지원하려면 백엔드 API 계약과 이슈 정의가 필요했다.

**추가 및 변경된 내용**

- 왼쪽 트리에서 선택된 문서 row에 선택 상태 스타일을 표시하도록 추가했다.
- graph hover와 외부 선택 상태를 분리해, 마우스 이동이나 pointer leave로 트리 선택 효과가 사라지지 않도록 변경했다.
- 화면의 다른 영역을 클릭하면 트리 선택과 graph focus가 함께 해제되도록 구성했다.
- `docs/issue/2026-06-11.md`에 문서와 Wiki page rename API 필요성, 원인, 해결 방향을 정리했다.
- `docs/Fruition_MVP_API_Contract.md`에 `PATCH /api/documents/{document_id}/rename`, `PATCH /api/wiki/pages/{wiki_page_id}/rename` 계약을 추가했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### feat: 문서 트리 클릭 시 graph node 포커스

**변경 배경**

- 왼쪽 문서 트리에서 특정 문서를 클릭했을 때 graph canvas에서 대응되는 raw/source/concept node를 바로 확인할 수 있어야 했다.

**추가 및 변경된 내용**

- 업로드 문서 트리 항목에 `raw:{document_id}` graph node id를 연결했다.
- `Source 문서`, `Concept 문서` 가상 항목에는 백엔드 Wiki graph node id를 연결했다.
- 트리 항목 클릭 시 Graph 컴포넌트가 해당 node를 선택/포커스하도록 외부 focus state를 추가했다.
- source/concept 가상 폴더는 백엔드 graph 데이터가 들어오면 자동으로 펼쳐지도록 변경했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### fix: raw와 source graph node 1:1 매핑

**변경 배경**

- 원본 파일은 MinIO에 raw 객체로 저장되고, source page는 raw 문서 1개에서 생성되는 1:1 결과물이다.
- 기존 프론트 graph 변환은 source page가 없는 문서를 progress/raw 단일 노드로만 표시해 raw와 source의 관계가 화면에 드러나지 않았다.

**변경된 내용**

- `GET /api/documents`의 모든 문서를 `raw:{document_id}` 노드로 생성하도록 변경했다.
- 각 raw 문서마다 `source:{document_id}` 노드를 항상 생성하고 `raw -> source` edge를 추가했다.
- 백엔드 Wiki graph에 실제 source page가 있으면 해당 source title을 사용하고, 아직 없으면 원본 파일명 기반의 임시 source node로 표시한다.
- 처리 중이거나 실패한 source/raw node에는 로딩 링을 표시해 pipeline 결과가 아직 확정되지 않았음을 보여준다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### fix: 원본 raw 카운트 기준 수정

**변경 배경**

- DB에는 업로드 문서가 7개 있지만 프론트 graph 상단의 `원본 raw`가 실패 문서 1개만 표시하고 있었다.
- 기존 계산은 graph node의 `kind=raw`만 세고 있어, 업로드 원본 수가 아니라 source page로 승격되지 않은 failed node 수를 보여주는 문제가 있었다.

**변경된 내용**

- graph 상단의 `원본 raw` 카운트를 `GET /api/documents`로 받은 전체 업로드 문서 수 기준으로 표시하도록 변경했다.
- `processing` 카운트도 graph node 종류가 아니라 실제 문서 상태가 `processing` 또는 `uploaded`인 문서 수 기준으로 표시하도록 변경했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

### fix: Source 문서 색상 표시 보정

**변경 배경**

- 사이드바의 source 문서 아이콘이 기본 트리 아이콘 색상인 회색으로 표시되어 graph의 source page 색상과 일관되지 않았다.

**변경된 내용**

- `Source 문서` 하위 항목의 source page 아이콘에 별도 class를 부여하고 노란색으로 표시되도록 스타일을 추가했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

---

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
