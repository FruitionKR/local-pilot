# Changelog — Frontend

React 프론트엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-07-04

### refactor: _lib 모듈 dead code 제거와 중복 통합

**변경 배경**

- `_lib/tree.ts`에 호출처가 없는 `buildWikiTreeGroups`(57줄)가 남아 있었고, 동일한 재귀 트리 순회 패턴이 5개 함수에 반복되어 있었다.
- `graph.ts`에 `NODE_PREFIX` 상수가 있음에도 `"raw:"`, `"source:"` 리터럴이 인라인으로 흩어져 있었고, `api.ts`의 에러 메시지 일부가 `ERROR_MESSAGES` 상수를 거치지 않았다.

**변경된 내용**

- `tree.ts`: dead 함수 `buildWikiTreeGroups` 삭제, 재귀 순회를 `mapTreeItemById` 헬퍼로 통합, wiki 그룹 ID를 상수로 추출, `raw:` 리터럴을 `makeRawId`로 대체.
- `graph.ts`: `makeRawId` 헬퍼 추가, `buildGraphFromBackend` 내부 리터럴을 `NODE_PREFIX`/`makeSourceId`/`makeRawId`로 통일.
- `api.ts`: `fetchBackendData`/`fetchWikiPage` 인라인 에러 문자열을 `ERROR_MESSAGES`로 이동, `fetchWikiPage`도 `parseErrorResponse`를 사용하도록 통일.
- `types.ts`: `QueryRelatedPageResponse`/`ChatMessageRelatedPageResponse` 공통 필드를 `RelatedPageBase`로 추출.
- 동작 변경 없음(순수 정리). export 시그니처는 `makeRawId` 추가 외 변화 없음.

**검증 결과**

- `npx tsc --noEmit` 통과.

## 2026-06-26

### fix: 원문 패널에서 자료관리 클릭 시 그래프 노드 선택 표시 해제

**변경 배경**

- 원문 미리보기가 열린 상태에서 그래프가 아닌 자료관리 영역을 클릭하면 `focusedGraphNodeId`는 해제되지만 그래프 내부 클릭 표시(`selectedNodeIdRef`)가 남아 노드가 계속 선택된 것처럼 보였다.

**변경된 내용**

- `useGraphCanvas`의 focus 동기화 effect가 `focusedNodeId`가 없거나 유효하지 않을 때 `setSelectedNode(null)`로 내부 클릭 표시도 해제하도록 수정했다(기존에는 focus 설정만 동기화).

**검증 결과**

- `npm run build`(타입체크 포함), `npm run lint` 통과.

### refactor: 프론트엔드 중복 로직 공통 유틸로 통합

**변경 배경**

- 동일한 로직(에러 메시지 추출, 조건부 className 합성, 그래프 색상/거리 계산, 드래그 leave 판정)이 여러 파일에 복붙되어 있어 재사용성과 일관성이 떨어졌다.

**변경된 내용**

- `_lib/errors.ts`의 `getErrorMessage`로 `error instanceof Error` 패턴 6곳을 통합했다.
- `_lib/classNames.ts`의 `cx`로 `filter(Boolean).join(" ")` className 합성 3곳을 통합했다.
- `AgentBody`에 섞여 있던 순수 포맷 함수 4개를 `agentFormatters.ts`로 추출했다.
- 그래프 색상 유틸(`hexToRgb`, `mixHexColor`)을 `graphColors.ts`로 모으고, `Math.hypot(dx, dy) || 0.01` 거리 계산을 `graphGeometry.safeDistance`로 통합했다.
- 드래그 leave 판정 중복을 `dragDrop.isPointerLeavingElement`로 통합했다.
- 동작 변경 없이 추출/이동만 수행했고, 구조 변경(Context 도입·훅 시그니처 재설계·타입 통합)은 범위에서 제외했다.

**검증 결과**

- `npm run build`(타입체크 포함), `npm run lint` 통과.

### feat: query citation 원문 block 하이라이트 개선

**변경 배경**

- query 답변의 `[n]` citation을 눌러 원문 근거를 확인할 때 markdown 원문이 일반 텍스트 block 목록처럼 표시되어 원문 구조를 보기 어려웠다.
- 여러 citation 근거를 확인할 때 번호별 구분이 어려웠고, 같은 원본 문서 안의 관련 근거를 한 번에 비교하기 어려웠다.

**변경된 내용**

- 원문 근거 확인 시 원본 markdown을 `MarkdownViewer`로 계속 렌더링하면서 해당 source block만 하이라이트하도록 변경했다.
- 답변 citation 버튼과 원문 block 하이라이트에 rank별 색상 팔레트를 적용했다.
- 한 문장에 `[1, 2]`처럼 여러 citation이 붙은 경우 각각 클릭 가능한 citation 버튼으로 렌더링한다.
- citation 클릭 시 같은 답변에서 같은 원본 문서에 속한 다른 근거 block도 함께 하이라이트해 `[1]`~`[5]` 근거를 색상으로 비교할 수 있게 했다.
- citation 클릭 시 실제 답변에 등장한 선택 rank만 원문 block 하이라이트에 사용하도록 제한했다.
- citation으로 원문을 열 때 자료 관리의 raw 원문 문서 row가 선택 표시되도록 했다.
- 자료 관리 트리에서 generated `Source 문서`/`Concept 문서` 그룹을 숨겼다.
- Figma `v1` title 노드 기준으로 자료 관리 header 상단 padding과 title 여백을 조정했다.
- graph 노드를 실제로 드래그한 뒤에는 클릭 선택 효과가 남지 않도록 했다.

**검증 결과**

- `npm run lint` 통과.

## 2026-06-20

### fix: 그래프 노드 선택 표시와 Markdown code block 여백 조정

**변경 배경**

- 그래프 노드를 드래그한 뒤 시간이 지나면 클릭/선택 이펙트가 사라져 현재 선택 상태를 구분하기 어려웠다.
- Markdown fenced code block의 텍스트가 검은색 블럭의 위쪽과 왼쪽에 너무 붙어 보여 가독성이 떨어졌다.

**변경된 내용**

- 그래프 canvas 내부의 hover 상태와 selected 상태를 분리해, 노드 클릭 또는 드래그 후 선택 마커가 유지되도록 조정했다.
- 외부 `focusedNodeId`가 `null`로 바뀌어도 그래프 내부 선택 상태를 강제로 지우지 않게 했다.
- Markdown code block의 내부 여백과 줄바꿈 스타일을 조정해 텍스트 시작 위치가 자연스럽게 보이도록 했다.
- Markdown code block 끝의 불필요한 빈 줄을 제거하고, 왼쪽 여백을 추가로 보정했다.
- Agent의 "찾은 자료" 카드를 `related_pages` 우선으로 렌더링하고, 현재 graph에 있는 page만 표시하도록 했다.
- 자료 관리 raw 문서 클릭과 graph raw 노드 더블 클릭이 같은 preview open 경로를 사용하도록 통합했다.
- 원본 문서 preview의 세로 스크롤바가 패널 오른쪽 테두리에 붙어 보이도록 조정했다.
- graph 노드 클릭 선택 이펙트가 hover 이펙트와 같은 렌더링 경로를 사용하도록 정리했다.

**검증 결과**

- `npm run lint` 통과.
- `npm run build` 통과.
- `.next` 캐시 삭제 후 `npm run dev`로 `http://localhost:3001` 기동 확인.

## 2026-06-17

### fix: Agent 새 질문 기준 scroll 동작 조정

**변경 배경**

- 오른쪽 Agent 패널에서 새 질문을 보낼 때 답변 생성에 맞춰 화면이 계속 아래로 이동해 ChatGPT 웹의 질문 기준 응답 흐름과 다르게 보였다.

**변경된 내용**

- 질문 전송 직후 임시 질문 박스를 먼저 표시하고, 해당 질문이 패널 상단에서 약 20px 아래에 오도록 scroll 기준을 변경했다.
- 전송 중 질문과 서버 응답 후 assistant 메시지를 하나의 active turn으로 유지해 로딩 블록이 실제 응답 블록으로 교체되며 생기는 layout shift를 줄였다.
- 답변 생성 중에는 기존 상태 목록, 찾은 자료, 답변 본문 구조를 유지한 채 질문 아래 영역에 단계적으로 표시되도록 했다.
- 새 질문이 마지막 항목이어도 상단 정렬이 가능하도록 새 응답 턴 아래에 임시 scroll 여유 공간을 추가하고, 답변 본문 노출 후에는 제거되도록 했다.
- 답변 완료 시 임시 여백 제거로 scroll 높이가 줄어들어도 질문 박스 기준 위치를 paint 전에 다시 보정하도록 했다.
- 서버 응답 후에는 임시 질문을 실제 채팅 기록의 질문으로 교체하고, 새 assistant 응답 animation도 해당 질문 기준 위치를 유지하도록 했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- `http://localhost:3001` 응답 `200 OK` 확인.

### fix: 접힌 Agent 패널 복원 버튼을 graph legend에 통합

**변경 배경**

- 오른쪽 Agent 패널을 접었을 때 복원 버튼이 graph filter chip과 분리되어 Figma `v1`의 축소 sidebar legend 구조와 달랐다.

**변경된 내용**

- Agent 패널이 접힌 상태에서는 `원본 raw`, `source page`, `concept page` chip 뒤에 패널 복원 아이콘 버튼이 같은 legend 그룹으로 표시되도록 변경했다.
- graph filter chip과 복원 아이콘 버튼의 간격, 크기, dark theme 색상을 Figma 구조에 맞춰 보정했다.

**검증 결과**

- `npm run lint` 통과.
- `http://localhost:3000` 응답 `200 OK` 확인.

### fix: 자료관리 프로젝트 추가 버튼 동작 수정

**변경 배경**

- 자료관리 header 오른쪽 버튼이 새 프로젝트를 추가하지 않고 첫 번째 프로젝트의 문서 업로드 picker를 열고 있었다.
- 자료관리 목록에 세로 scrollbar가 생길 때 tree 항목 위치가 흔들릴 수 있었다.

**변경된 내용**

- 자료관리 header 오른쪽 버튼을 새 프로젝트 추가 동작에 연결했다.
- 새 프로젝트 추가 직후 프로젝트 이름 편집 상태로 진입하도록 했다.
- 자료관리 scroll 영역에 scrollbar gutter를 예약해 scrollbar 표시 여부와 관계없이 기존 padding 위치가 유지되도록 했다.
- Agent 채팅 기록 최초 로딩 후 가장 최근 메시지가 보이도록 scroll 위치를 하단으로 맞췄다.
- Graph filter의 `source page` chip 아이콘을 `source_page.svg` 자산으로 변경했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.

### refactor: CSS 영역별 세부 파일 분리

**변경 배경**

- `document-sidebar`, `agent-panel`, `graph` CSS가 영역 단위 파일로는 여전히 커서 세부 컴포넌트별 스타일 위치를 찾기 어려웠다.
- Figma 기준 보정이 반복되는 영역을 shell, 상태, 결과, tree, node 등 실제 UI 역할 단위로 더 좁혀 관리할 필요가 있었다.

**변경된 내용**

- `frontend/app/_styles/document-sidebar/` 아래로 shell, project section, tree row, context menu 스타일을 분리했다.
- `frontend/app/_styles/agent-panel/` 아래로 shell, conversation, status, answer, results, composer 스타일을 분리했다.
- `frontend/app/_styles/graph/` 아래로 stage, filters, lines, nodes 스타일을 분리했다.
- dark override도 요청 범위에 맞춰 `dark/document-sidebar/`, `dark/agent-panel/`, `dark/graph/` 하위 파일로 세분화했다.
- 기존 `document-sidebar.css`, `agent-panel.css`, `graph.css`와 dark entry 파일은 import entry로 유지해 cascade 순서를 보존했다.

**검증 결과**

- 분리된 CSS 파일을 순서대로 합친 결과가 직전 entry 파일 내용과 동일함을 확인했다.
- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- `http://localhost:3000` 응답 `200 OK` 확인.

### refactor: CSS 파일 구조 분리

**변경 배경**

- `frontend/app/globals.css` 한 파일에 base, workspace, sidebar, graph, Agent panel, dark override 스타일이 모두 들어 있어 Figma 보정 시 변경 범위를 파악하기 어려웠다.

**변경된 내용**

- 전역 CSS entry를 `frontend/app/_styles/globals.css`로 이동하고, `layout.tsx`의 import 경로를 갱신했다.
- 기존 CSS 내용을 base/workspace/rail/document-sidebar/graph/agent-panel/responsive 영역과 dark override 영역으로 분리했다.
- 분리 과정에서 기존 cascade 순서와 CSS 내용을 유지하도록 파일 import 순서를 구성했다.

**검증 결과**

- 분리된 CSS 파일을 순서대로 합친 결과가 기존 `frontend/app/globals.css`와 동일함을 확인했다.
- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- `http://localhost:3000` 응답 `200 OK` 확인.

### fix: Agent 패널 Figma 보정과 sidebar tree 안정화

**변경 배경**

- backend polling 시 sidebar tree가 실제 데이터 변경 없이도 새 객체로 교체되어 채팅 답변 표시와 겹칠 때 새로고침처럼 보일 수 있었다.
- Figma `v1`의 Agent 패널 node와 비교했을 때 결과 card badge 색상, body 상단 여백, 패널 하단 간격이 일부 어긋나 있었다.
- 자료 관리 header와 Agent sidebar의 접기/추가 아이콘이 Figma 기준보다 작게 표시됐다.
- 원본문서 preview 유무에 따라 자료 관리 sidebar와 원본문서 패널의 우상단 곡률과 border가 Figma 기준과 어긋나 있었다.

**변경된 내용**

- backend 데이터 merge 결과가 기존 sidebar tree와 같으면 기존 project/tree 참조를 유지하도록 비교 로직을 추가했다.
- Agent 패널의 body 상단 여백, 하단 inset, 결과 card의 file icon과 badge 색상을 Figma dark panel 기준으로 보정했다.
- 자료 관리 header 오른쪽 버튼과 Agent sidebar collapse 버튼 내부 아이콘 크기를 28px 기준으로 보정했다.
- 원본문서 preview가 없을 때는 자료 관리 sidebar 우상단을 둥글게, preview가 있을 때는 원본문서 패널 우상단을 둥글게 표시하도록 보정했다.
- 둥근 모서리 영역의 상단/우측 border가 끊기지 않도록 sidebar와 원본문서 패널 border를 보정했다.
- file icon 렌더링을 inline SVG override 대신 `frontend/svg/file.svg` 자산을 그대로 사용하도록 변경했다.
- Query Agent의 `Source` 결과 card는 `frontend/svg/source.svg` 자산을 사용하도록 분기했다.
- 자료 관리 sidebar의 source 항목도 `frontend/svg/source.svg` 자산을 사용하도록 변경했다.
- 자료 관리 tree에서 `failed`, `completed`, 로딩 상태 chip 표기를 제거했다.
- 자료 관리 heading typography를 Figma 기준의 Pretendard 20px, 600 weight, 120% line-height로 보정했다.
- 자료 관리 sidebar header는 고정하고 tree 영역만 sidebar 내부에서 scroll되도록 구조를 분리했다.
- 자료 관리 header 오른쪽 버튼은 sidebar collapse 대신 기존 자료 추가 picker를 열도록 되돌렸다.
- 자료 관리 header 오른쪽 버튼 내부 아이콘을 버튼 영역과 동일한 28px × 28px로 보정했다.
- graph filter chip padding과 typography를 Figma legend 기준으로 보정했다.
- graph filter의 `source page` chip 아이콘도 `source.svg` 자산을 사용하도록 변경했다.
- graph filter chip 그룹이 graph-stage 오른쪽 끝에 붙도록 위치를 보정했다.
- Agent sidebar collapse 버튼 내부 아이콘을 28px × 28px로 보정했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- `http://localhost:3001` 응답 `200 OK` 확인.
- 변경 파일 기준 secret 후보 검색에서 실제 비밀값 없음.

### feat: Agent 응답 연출과 그래프 아이콘 보정

**변경 배경**

- Query Agent 답변이 한 번에 표시되어 Figma 기준의 단계적 응답 흐름과 차이가 있었다.
- raw/source/concept 자료가 sidebar, 결과 card, graph에서 서로 다른 아이콘과 색상 체계로 보여 식별성이 떨어졌다.
- 자료 관리 sidebar를 접고 graph workspace를 넓혀 보는 동작이 필요했다.

**변경된 내용**

- 새 assistant 응답을 상태 목록, 근거 자료, 답변 본문 순서로 단계적으로 노출하고 최신 메시지로 부드럽게 scroll되도록 조정했다.
- raw/source/concept 항목의 file icon 색상과 결과 badge 색상을 통일하고, raw graph node에 `raw.svg` 자산을 적용했다.
- 자료 관리 sidebar 접기/복원 버튼을 추가하고 sidebar 접힘 상태에서 graph와 원문 preview 위치가 남은 workspace를 사용하도록 보정했다.

**검증 결과**

- `npm run lint` 통과.
- `./node_modules/.bin/tsc --noEmit` 통과.
- 변경 파일 기준 secret 후보 검색에서 실제 비밀값 없음.

### fix: Figma 기준 그래프와 Agent UI 보정

**변경 배경**

- Figma `v1` 화면과 비교했을 때 중앙 검색 바, 왼쪽 rail 아이콘, 오른쪽 Agent 패널, graph node 색상과 hover 전환이 실제 화면과 어긋나 있었다.
- graph hover 시 화면 dimming과 링크 강조가 즉시 바뀌어 마우스를 빠르게 움직이면 화면이 버벅이는 것처럼 보였다.
- Query Agent 채팅 기록 로딩 실패와 질의 처리 실패 상태가 같은 UI로 표시되어 실제 오류 원인을 구분하기 어려웠다.

**변경된 내용**

- Figma 값 기준으로 topbar 검색창, Agent 패널, 좌측 rail 아이콘, source/concept/raw node 색상과 크기를 보정했다.
- `search.svg`, `CollectionOutLine.svg` 등 교체된 SVG 자산을 실제 UI 렌더링 경로에 연결했다.
- Query Agent 답변 UI를 상태 목록, 검색 결과, 답변 본문 영역으로 나누고 채팅 기록 로딩 실패와 질의 실패 표시를 분리했다.
- graph loading node 표현과 연결 수 기반 node 크기 확대를 제거하고, source page를 기준으로 관련 node가 원형 배치되도록 변경했다.
- graph hover 시 node 색상, 화면 dimming, 링크 강조가 노드별 보간값을 따라 천천히 fade in/out 되도록 조정했다.

**검증 결과**

- `npm run build` 통과.
- `http://localhost:3000` 응답 `200 OK` 확인.
- `/api/wiki/graph` 정상 응답 확인.

---

## 2026-06-16

### feat: Query Agent 채팅과 원문 viewer 연동

**변경 배경**

- 로컬 테스트에서 질문 입력 후 Query API 응답, 이전 채팅 기록, 근거 자료 클릭, source/concept 원문 확인 흐름을 한 화면에서 검증할 수 있어야 했다.
- Agent 패널의 정적 목업 상태와 중복 실행 상태 표시가 실제 query 흐름과 맞지 않았다.

**변경된 내용**

- Agent 입력창이 `POST /api/query`를 호출하고, `GET /api/chat/messages`로 이전 질문/답변 기록을 유지해 표시하도록 연결했다.
- assistant 답변을 markdown viewer로 렌더링하고, 문장 단위 가독성을 위해 마침표 뒤 줄바꿈을 보정했다.
- 근거 자료 card 클릭 시 `wiki_page_id` 또는 `document_id` 기반 source page를 원문 viewer로 열도록 연결했다.
- source/concept 원문 preview 패널이 Wiki page 상세 API의 markdown을 GitHub markdown viewer에 가까운 형태로 표시하도록 조정했다.
- 자료 관리 sidebar와 원문 viewer resize handle, 채팅 영역 scrollbar를 다크 워크스페이스 기준으로 보정했다.

**검증 결과**

- `npm run lint` 통과.
- `npm run build` 통과.
- `http://localhost:3000` 응답 `200 OK` 확인.

### feat: 그래프 원본문서 패널 상호작용 보강

**변경 배경**

- Figma `v1`의 다크 워크스페이스 기준으로 graph node 선택 표시, filter chip 위치, 원본문서 overlay 패널 동작을 맞출 필요가 있었다.
- 원본문서 패널이 graph 영역을 덮으면서도 패널 폭 조절에 따라 graph canvas가 남은 영역 기준으로 다시 scale되어야 했다.

**변경된 내용**

- graph canvas node 더블클릭 시 왼쪽 자료 트리 클릭과 동일하게 원본문서 미리보기 패널이 열리도록 연결했다.
- 원본문서 패널 오른쪽 resize handle을 추가하고, 패널 폭 변경 시 graph canvas 영역이 자동으로 재계산되도록 구성했다.
- graph node 선택 marker를 Figma `select.svg` 기준의 radial highlight 표현으로 보정했다.
- graph filter chip을 canvas 오른쪽 위에 배치하고 `processing` chip을 제거했다.
- graph scale, pan clamp, layout cache 버전을 조정해 node가 화면 밖으로 사라지거나 과도하게 뭉쳐 보이는 문제를 완화했다.

**검증 결과**

- `./node_modules/.bin/tsc --noEmit` 통과.
- `http://localhost:3001` 응답 `200 OK` 확인.
- 백엔드가 실행되지 않은 상태에서는 Next proxy가 `localhost:8080` 연결 실패를 기록한다.

### refactor: 홈 화면 렌더링 컴포넌트 분리

**변경 배경**

- `frontend/app/page.tsx`에 topbar, rail, Agent panel, source preview, SVG 렌더링 코드가 함께 있어 화면 단위 수정과 Figma 비교 작업의 변경 범위가 커지고 있었다.

**변경된 내용**

- `frontend/app/_components/` 폴더를 추가하고 `TopBar`, `RailNavigation`, `AgentPanel`, `SourcePreviewPanel`, `SvgIcon`을 분리했다.
- `page.tsx`는 홈 화면 상태 관리와 문서 트리/graph 흐름을 중심으로 남기고, 정적 UI 조각은 개별 컴포넌트 파일에서 관리하도록 정리했다.
- rail 항목과 SVG asset export를 컴포넌트 폴더로 옮겨 sidebar/agent/graph에서 같은 아이콘 렌더러를 재사용하도록 구성했다.

**검증 결과**

- `npm run lint` 통과.
- `npm run build` 통과.

### fix: Agent 패널 상태 표시와 입력창 여백 보정

**변경 배경**

- Figma 오른쪽 채팅 sidebar와 비교했을 때 명령 실행 상태 마커의 완료/진행중/추후 실행 표현이 다르게 보였다.
- Agent 메시지 입력창이 패널 하단 여백 없이 붙어 보여 Figma 기준의 composer 위치와 맞지 않았다.
- `page.tsx`의 홈 화면 JSX가 한 함수에 몰려 있어 Agent/sidebar 영역을 추후 분리하기 어렵게 되어 있었다.

**변경된 내용**

- Agent 상태 목록의 완료 마커를 10px 노란 원형으로, 진행중 마커를 노란 테두리와 어두운 배경의 10px 원형으로 보정했다.
- 추후 실행 상태는 `frontend/svg/Ellipse.svg` 자산을 사용하도록 연결하고, 어두운 sidebar 배경에 맞게 SVG 색상을 조정했다.
- Agent composer 하단 row와 margin을 조정해 입력창 아래 16px 여백을 확보했다.
- 홈 화면 JSX를 `TopBar`, `RailNavigation`, `DocumentSidebar`, `AgentPanel` 등 내부 렌더링 컴포넌트로 1차 분리했다.

**검증 결과**

- `npm run lint` 통과.
- `npm run build` 통과.

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
- `자료 관리` 헤더 hover 또는 focus 시 표시되는 `switch.svg` 버튼으로 문서 업로드를 실행하도록 정리했다.
- `.project-toggle`과 `.section-title` hover 배경이 사이드바 행 폭을 채우도록 보정했다.
- topbar의 메뉴 아이콘 버튼을 `부산대학교` 프로젝트 약칭인 `부` 배지로 교체했다.
- Figma `workspace` 노드 기준으로 topbar 프로젝트 배지 간격과 `부` 배지 typography를 보정했다.
- 백엔드 API 오류가 발생하면 graph 빈 상태 위치에 오류 메시지를 표시하도록 변경했다.
- graph API 오류 메시지를 실패 상태와 구분되도록 빨간색 계열로 표시했다.
- Figma `legend`와 chat composer 기준으로 filter chip 위치와 Agent 입력창 패딩을 보정했다.
- Figma `process` 노드 기준으로 Agent 상태 목록의 제목, step, 시간 표시 간격을 보정했다.
- Agent 상태 목록 완료 아이콘을 `chat_check.svg`로 교체하고 step 사이 세로 점선을 추가했다.
- Figma chat frame 기준으로 Agent 패널 header/body/composer row 위치를 다시 보정했다.

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
