# llmPipeline Skill 작성·검토·게시 흐름

## 1. 문서 범위

이 문서는 현재 `llmPipeline`이 자연어 또는 사용자가 작성한 Markdown으로 Agent Skill을 제안하고, 보안 검토 후 게시하는 흐름을 설명한다.

현재 코드 구현을 기준으로 관리 화면의 단발 작성, 채팅의 멀티턴 작성, 완료 AgentRun 재사용, 게시와 수정까지 다룬다. Frontend·Spring Backend의 미연결 범위는 [15. 구현 경계와 운영 주의점](#15-구현-경계와-운영-주의점)에 따로 정리한다.

### 핵심 계약

| 항목 | 계약 |
| --- | --- |
| 제안 | `proposal_ready`까지 DB에 저장하지 않는다. |
| 권한 | capability와 Tool은 사용자 입력이 아니라 두 intent 판정의 합의와 서버 allowlist로 결정한다. |
| 참조 | 권한을 확인한 Workspace 문서만 읽고, LLM에는 Markdown 구조만 전달한다. |
| 보안 | 입력·참조·생성 결과를 검사하며, 최종 게시에서도 전체 검증을 다시 수행한다. |
| 재생성 | 위험 구간을 치환한 뒤 생성과 intent 판정을 최대 한 번만 재시도한다. |
| 저장 범위 | personal은 사용자 계정, team은 현재 Workspace를 기준으로 저장하고 중복을 검사한다. |

### 구현 지도

| 책임 | 기준 코드 |
| --- | --- |
| HTTP 진입점과 schema | `llmPipeline/app/modules/skill/interfaces/http/routes.py`, `llmPipeline/app/modules/skill/interfaces/http/schemas.py` |
| 공통 작성 orchestration | `llmPipeline/app/modules/skill/application/author_skill.py` |
| 게시·수정 관리 | `llmPipeline/app/modules/skill/application/manage_skill.py` |
| 작성·intent LLM adapter | `llmPipeline/app/modules/skill/infrastructure/chat_completions_skill_authoring_generator.py` |
| 작성·분류·검증 prompt | `llmPipeline/prompts/skill_authoring.system.md`, `llmPipeline/prompts/skill_intent_classifier.system.md`, `llmPipeline/prompts/skill_intent_verifier.system.md` |
| 참조 구조·보안·Tool 정책 | `llmPipeline/app/modules/skill/domain/reference_template.py`, `llmPipeline/app/modules/skill/domain/safety.py`, `llmPipeline/app/modules/skill/domain/policy.py` |
| PostgreSQL 저장 | `llmPipeline/app/modules/skill/infrastructure/postgres_skill_repository.py` |
| 채팅 orchestration과 routing | `llmPipeline/app/modules/agent/application/handle_agent_turn.py`, `llmPipeline/app/modules/agent/infrastructure/chat_completions_turn_router.py` |

## 2. 한눈에 보는 전체 흐름

```text
관리 화면                                  채팅
POST /skills/author                        POST /agent/turn
  │                                          │
  │                                          ▼
  │                                     Agent turn router
  │                                          ├─ 기존 Skill 사용 -> 일반 Agent 작업
  │                                          ├─ 새 Skill 작성 -> skill_authoring
  │                                          └─ 완료 작업 재사용 -> skill_draft_proposal
  │                                          │
  │                                     개인/team 범위 확인
  │                                          │
  └──────────────────────┬───────────────────┘
                         ▼
                 AuthorSkillUseCase
                         │
                         ▼
                입력 길이·형식 검사
                         │
                         ▼
          규칙 기반 보안·개인정보·credential 검사
                ├─ 차단 -> blocked, 미저장
                └─ 통과
                         │
                         ▼
            선택된 참조 문서 권한 조회·검사
                         │
                         ▼
             Markdown 구조만 추출해 LLM 전달
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
       intent classifier      intent verifier
              └──────────┬──────────┘
                         ▼
          decision·skill_kind·reference_mode·Tool 비교
              ├─ 하나라도 unsupported -> 400
              ├─ 모호함·불일치
              │    ├─ 채팅 -> clarification_required
              │    └─ 단발 API -> 400
              └─ 전체 일치
                         │
                         ▼
             서버 capability·Tool 정책 검증
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
       fixed-template          일반 작성
       서버 구조 조립           작성 LLM 호출
              └──────────┬──────────┘
                         ▼
               생성 결과 보안 재검사
              ├─ 차단 -> blocked, 미저장
              └─ 통과 -> proposal_ready, 미저장
                         │
                         ▼
              사용자 검토·수정·재생성
                         │
                         ▼
             POST /skills/author/publish
                         │
                         ▼
             같은 분류·검증·보안검사 재실행
              ├─ 실패 -> 저장하지 않음
              └─ 통과 -> published version 저장
                              + 기본 자동 라우팅 ON
```

관리 화면과 채팅의 API 진입점은 다르지만, 자연어 해석 이후의 작성·검증·게시 핵심 로직은 `AuthorSkillUseCase` 하나로 합류한다.

## 3. Skill 작성에서 사용하는 핵심 값

### 커맨드 이름

Skill의 `name`은 별도 표시 제목이 아니라 `/` 뒤에 사용하는 커맨드 식별자다. `slug`도 같은 값을 사용한다.

허용 형식은 다음과 같다.

```text
lowercase letters + numbers + hyphen
최대 63자

예: meeting-notes
호출: /meeting-notes
```

한글, 공백, 대문자, underscore는 허용하지 않는다. 사용자가 이름을 보내지 않으면 작성 LLM이 후보를 생성하고 서버가 같은 규칙으로 검증한다.

### 범위

| scope | 저장 범위 | 중복 검사 범위 | 접근 방식 |
| --- | --- | --- | --- |
| `personal` | 사용자 계정 | 같은 사용자 계정 | 어느 Workspace에서든 소유자가 사용 |
| `team` | 현재 Workspace | 같은 Workspace | 해당 Workspace 구성원이 사용 |

personal Skill은 저장 시 `workspace_id=null`, `owner_user_id=user_id`가 된다. team Skill은 `workspace_id`를 유지하고 `owner_user_id=null`이 된다.

### capability와 Tool

사용자는 자연어 작성 API에 `capabilities`나 `allowed_tools`를 입력하지 않는다. 두 intent LLM의 합의 결과를 서버가 고정 allowlist로 검증한다.

현재 capability는 다음 네 종류다.

| skill kind / capability | 의미 |
| --- | --- |
| `document-create` | Markdown 내용 또는 새 문서 작성 |
| `document-edit` | 기존 Markdown 또는 문서 수정 |
| `folder-organize` | Workspace 폴더·문서 생성, 이름 변경, 이동 |
| `template` | 재사용 가능한 문서 템플릿 생성 또는 적용 |

Tool은 capability가 허용한 집합 안에서만 선택할 수 있다. mutation Tool이 있으면 planning read를 위한 `list_root_items`, `list_folder_children`가 자동으로 필요하다.

`SkillAuthoringResponse`에는 이 내부 capability와 Tool을 포함하지 않는다. 다만 기존 관리용 `SkillResponse`와 내부 `/skills/preview` 계약에는 현재도 version의 capability와 Tool이 포함되어 있으므로, 사용자 화면에 그대로 노출하는 계약으로 해석하면 안 된다.

## 4. 관리 화면의 단발 작성 — `POST /skills/author`

**역할과 입력**

짧은 자연어나 사용자가 직접 작성한 Markdown을 게시 전 Skill 제안으로 바꾼다. 이 요청은 제안을 DB에 저장하지 않는다.

```json
{
  "workspace_id": "workspace-id",
  "user_id": "user-id",
  "scope_type": "personal",
  "name": "meeting-notes",
  "description": null,
  "instruction": "회의 내용을 결정 사항과 다음 작업으로 나누어 정리해줘",
  "authoring_mode": "enhance",
  "reference_document_ids": []
}
```

| 필드 | 필수 | 현재 제한 |
| --- | --- | --- |
| `workspace_id` | 예 | 빈 문자열 불가 |
| `user_id` | 예 | 빈 문자열 불가 |
| `scope_type` | 예 | `personal` 또는 `team` |
| `name` | 아니오 | lowercase-hyphen, 최대 63자 |
| `description` | 아니오 | 최대 500자 |
| `instruction` | 예 | `preserve` 최대 30,000자, 나머지 모드 최대 4,000자 |
| `authoring_mode` | 아니오 | 기본 `enhance` |
| `reference_document_ids` | 아니오 | 최대 3개, 중복·빈 ID 불가 |

관리 화면 요청은 `allow_clarification=False`로 실행한다. 작성 LLM이 질문을 반환하면 일반 placeholder를 사용한 제안을 한 번 더 요구한다. intent 판정 자체가 모호하거나 두 판정이 다르면 질문 UI로 전환하지 않고 `400`을 반환한다.

### 작성 mode

| mode | 입력 처리 | 사용 시점 |
| --- | --- | --- |
| `preserve` | 사용자의 `instruction`을 최종 Skill 본문으로 유지 | 직접 작성한 Markdown 게시 전 검토, 수정 내용 보안 재검토 |
| `enhance` | 작성 LLM이 짧은 자연어를 재사용 가능한 Markdown으로 구체화 | 관리 화면의 `AI로 구체화`, 채팅 최초 작성 |
| `regenerate` | 위험 구간을 `[보안상 제거됨]`으로 치환한 뒤 한 번 안전하게 재작성 | 차단 화면의 `AI로 재생성` |

`preserve`도 LLM을 전혀 사용하지 않는다는 뜻은 아니다. 본문을 바꾸지 않을 뿐, intent 분류·검증과 의미 기반 안전 검사는 그대로 수행한다.

## 5. 채팅 작성 — `POST /agent/turn`

### 새 Skill 요청 판별

채팅 router는 사용자가 새 Skill 자체를 만들어 달라고 명시한 경우에만 `skill_authoring`을 허용한다.

```text
"회의록 작성 Skill을 만들어줘"
  -> skill_authoring

"회의록 Skill을 사용해서 문서를 작성해"
  -> 기존 Skill을 사용하는 문서 작업

"방금 작업 방식대로 Skill로 만들어줘"
  -> skill_draft_proposal
```

LLM router가 기존 Skill 사용 요청을 새 Skill 작성으로 잘못 분류하면 서버 contract guard가 실패 사유를 넣어 한 번 다시 분류한다. 같은 오류가 반복되면 route contract 오류로 끝낸다.

### 범위 확인

채팅 최초 요청에 `skill_scope_type`이 없고 메시지에서도 개인/team을 확인할 수 없으면 다음 질문을 반환한다.

```text
개인 스킬로 만들까요, 현재 팀 스킬로 만들까요?
```

다음 turn에서 사용자가 “개인으로”처럼 짧게 답해도 `recent_conversation_summary`에 기존 Skill 생성 요청이 유지되어 있으면 다시 `skill_authoring`으로 돌아올 수 있다.

### 요청 예시

```json
{
  "message": "회의록 작성 Skill을 만들어줘",
  "workspace_id": "workspace-id",
  "user_id": "user-id",
  "skill_scope_type": "personal",
  "skill_authoring_mode": "enhance",
  "skill_reference_document_ids": [],
  "conversation_context": {
    "recent_conversation_summary": null,
    "reference_context": {},
    "pending_skill_proposal": null
  }
}
```

채팅은 `allow_clarification=True`이므로 intent가 모호하거나 classifier와 verifier가 다르면 다음처럼 보충 질문을 반환한다.

```text
이 Skill이 수행할 작업이 문서 작성, 문서 수정, 폴더 정리, 템플릿 중 무엇인지 알려 주세요.
```

## 6. 입력 보안검사

**규칙 기반 검사**

LLM을 호출하기 전에 instruction, description과 참조 문서 전체 Markdown을 검사한다.

현재 차단 범주는 다음을 포함한다.

- 승인 우회
- 권한 무시·상승
- shell·SQL 직접 실행
- 시스템 정책·이전 지시 무시
- system prompt 노출 요구
- system/developer 역할 변경
- 이메일, 국내·국제 전화번호
- 날짜·검증번호를 확인한 주민등록번호
- Luhn 검증을 통과한 결제 카드번호와 계좌번호 문맥
- `이름`, `주소` 등 개인정보 필드에 들어간 실제 값이나 명시적 placeholder
- private key, access key, GitHub·Slack token, JWT·Bearer token, password 같은 credential 형태

개인정보 필드가 비어 있거나 밑줄만 있는 템플릿은 허용한다. 표 header 역시 구조만 있으면 허용하지만, 실제 값이나 `[이름]`, `[주소]` 같은 명시적 개인정보 placeholder가 들어간 행은 차단한다.

```text
이름:                  -> 허용
이름: __________       -> 허용
이름: 홍길동           -> 차단
이름: [이름]           -> 차단
```

한 입력에서 여러 개인정보나 credential이 발견되면 첫 항목만 반환하지 않고, 겹치는 범위를 제거한 뒤 모든 위치를 문서 순서대로 반환한다.

발견된 문제는 다음 위치 정보를 가진다.

```json
{
  "category": "approval_bypass",
  "text": "승인 없이",
  "reason": "Skill은 시스템 권한·승인·tool 정책을 변경할 수 없습니다.",
  "severity": "blocked",
  "start": 4,
  "end": 9,
  "source_type": "instruction",
  "reference_document_id": null
}
```

`preserve`와 `enhance`는 위험 입력을 조용히 지우지 않고 `blocked`로 반환한다. `regenerate`만 정확한 위치를 `[보안상 제거됨]`으로 치환한 뒤 재생성을 진행한다.

**의미 기반 검사**

정해진 marker와 정확히 일치하지 않는 prompt injection, 비정형 개인정보·주소·인증정보와 사내 기밀정보도 작성 LLM이 `blocked`로 반환할 수 있다. 빈 개인정보 필드, 밑줄만 있는 입력란, 표 header와 일반 `[item]` 구조는 개인정보로 판단하지 않는다. LLM은 원문에 실제로 존재하는 substring과 source를 반환해야 하며, 서버가 위치를 다시 찾지 못하면 잘못된 LLM 결과로 거절한다.

LLM이 찾은 위험 구간으로 `regenerate`를 수행할 때는 최초 생성이 차단된 경우에만 위험 구간을 제거하고 intent 판정과 생성을 한 번 더 수행한다. 총 생성 시도는 최대 두 번이며, 두 번째 결과가 다시 차단되면 추가 재시도나 부분 게시 없이 `blocked`로 반환한다.

## 7. 참조 문서 조회와 구조 추출

### 조회 경계

사용자는 로컬 파일을 직접 업로드하지 않는다. Workspace에 이미 저장된 Markdown 문서 ID만 선택한다.

llmPipeline은 다음 Spring 내부 endpoint를 호출하도록 구현되어 있다.

```http
POST /internal/agent/skill-authoring/references/read
X-Agent-Service-Token: {AGENT_INTERNAL_TOKEN}

{
  "workspace_id": "workspace-id",
  "user_id": "user-id",
  "document_id": "document-id"
}
```

Spring은 Workspace membership과 문서 read 권한을 확인한 뒤 `markdown`을 반환해야 한다. 자연어 Skill 작성에는 AgentRun이 없으므로 임의 `run_id`를 만들거나 AgentRun Tool Gateway를 우회하지 않는다.

현재 Spring endpoint는 미구현이다. 따라서 참조 없는 작성은 llmPipeline에서 처리할 수 있지만, 실제 참조 문서 E2E는 이 endpoint가 연결된 뒤 동작한다.

### 크기와 내용 제한

- 최대 참조 문서 수: 3개
- 문서 하나의 Markdown: 최대 40,000자
- 전체 참조 Markdown: 최대 80,000자
- 빈 문서 불가
- 참조 전체 원문에도 규칙 기반 보안검사 적용

### LLM에 전달하는 구조

참조 전체 본문을 그대로 보내지 않는다. 다음 구조만 추출한다.

- Markdown heading
- 목록 marker와 `[item]` placeholder
- 표 header와 separator

fenced code block 안의 내용은 구조 추출에서 제외한다. 일반 본문, resource ID와 목록 항목의 실제 값은 전달하지 않는다.

예를 들어 다음 참조가 있다.

```markdown
# 8월 제품 회의

## 참석자

- 재형
- 철수

## 결정 사항

| 담당자 | 기한 |
| --- | --- |
| 재형 | 8월 20일 |
```

추출 구조는 다음과 같다.

```markdown
# 8월 제품 회의
## 참석자
- [item]
- [item]
## 결정 사항
| 담당자 | 기한 |
| --- | --- |
```

## 8. 두 LLM의 독립 intent 판정

참조 조회와 1차 보안검사가 끝나면 작성 LLM보다 먼저 intent classifier와 verifier를 각각 호출한다.

```text
같은 사용자 payload
  ├─ skill_intent_classifier.system.md
  └─ skill_intent_verifier.system.md

각 호출은 상대 판정 결과를 전달받지 않는다.
```

현재 구현은 같은 `ChatCompletionsJsonClient`와 설정 model을 사용하지만, 서로 다른 system prompt로 두 번 독립 호출한다. 즉 서로 다른 provider나 model을 사용한다는 의미는 아니다.

두 LLM에는 다음 데이터만 전달한다.

```json
{
  "instruction": "회의록 작성 Skill을 만들어줘",
  "requested_description": null,
  "references": [
    {"markdown_structure": "# 회의록\n## 결정 사항\n- [item]"}
  ]
}
```

각 결과 계약은 다음과 같다.

```json
{
  "decision": "supported",
  "skill_kind": "template",
  "reference_mode": "fixed-template",
  "allowed_tools": [
    "list_root_items",
    "list_folder_children",
    "create_document"
  ]
}
```

### 판정 합류 규칙

| classifier | verifier | 결과 |
| --- | --- | --- |
| 둘 다 `supported`이고 전체 값 일치 | 동일 | 다음 작성 단계 진행 |
| 하나라도 `unsupported` | 무관 | 지원 Agent action이 아니므로 거절 |
| 하나라도 `ambiguous` | 무관 | 채팅은 질문, 단발 API는 `400` |
| 둘 다 supported지만 kind/reference/Tool 불일치 | 불일치 | 채팅은 질문, 단발 API는 `400` |

`unsupported`나 `ambiguous` 결과는 `skill_kind=null`, `allowed_tools=[]`여야 한다. 그렇지 않으면 권한을 함께 부여한 잘못된 판정으로 거절한다.

`supported` 결과는 서버가 다음을 다시 확인한다.

- `skill_kind`가 고정 capability allowlist에 존재하는가?
- `fixed-template`이면 `skill_kind=template`인가?
- 참조가 없는데 reference mode가 참조 사용을 주장하지 않는가?
- Tool이 알려진 allowlist에 존재하는가?
- Tool이 해당 capability 범위 안에 있는가?
- mutation Tool에 필요한 planning read Tool이 포함됐는가?

두 판정이 합의한 하나의 `skill_kind`만 capability로 사용한다. 뒤의 작성 LLM은 capability나 Tool을 다시 선택하지 않는다.

## 9. 참조 용도 분기와 Markdown 작성

### 참조 용도

| reference mode | 의미 | 결과 생성 방식 |
| --- | --- | --- |
| `none` | 참조를 사용하지 않음 | 일반 작성 LLM 결과 사용 |
| `structure-reference` | 참조의 구조·스타일만 참고 | 일반 작성 LLM 결과 사용 |
| `fixed-template` | 선택 문서 구조를 출력 템플릿으로 고정 | 서버가 추출 구조를 직접 조립 |

참조 문서가 있다는 사실만으로 `template` capability가 되지 않는다. 두 intent LLM이 사용자의 명시적인 요청을 보고 `fixed-template + template`에 합의해야 한다.

### 일반 작성 LLM

intent 합의 후 `skill_authoring.system.md`가 name, description과 instructions Markdown을 생성한다.

작성 LLM은 다음을 다시 결정하지 않는다.

- 지원 여부
- capability
- allowed Tool
- 참조가 고정 템플릿인지 여부

이 값들은 앞의 intent 판정 결과로 제공된다. 작성 LLM은 안전한 metadata와 Markdown 작성에만 집중한다.

### 고정 템플릿

`fixed-template`은 정확히 한 문서만 허용한다. 서버가 추출한 구조가 비어 있으면 `400`이다.

작성 LLM이 반환한 `instructions_markdown`은 사용하지 않고 서버가 다음 형식으로 조립한다.

````markdown
# 작성 규칙

- 입력 내용을 아래 템플릿 구조에 맞춰 작성한다.
- 제목, 섹션 순서, 목록과 표 구조를 변경하지 않는다.
- 제공되지 않은 내용을 추측하지 않는다.

# 고정 출력 템플릿

```markdown
# 회의록
## 참석자
- [item]
## 결정 사항
- [item]
```
````

`regenerate`에서도 기존 `# 고정 출력 템플릿` block을 추출해 유지한다. 이 경우 두 intent LLM 역시 `template` skill kind에 합의해야 하며 서버가 다른 capability를 임의로 덮어쓰지 않는다.

## 10. 결과 검증과 미저장 proposal

작성 결과는 다음 검사를 통과해야 한다.

- name/slug의 lowercase-hyphen 규칙
- description 최대 500자
- instructions 최대 30,000자, 최대 500줄
- capability가 비어 있지 않은가?
- capability와 Tool이 호환되는가?
- 생성된 name, description, instructions에 위험 marker, 개인정보·기밀정보나 credential이 없는가?
- 참조 문서 ID 같은 고정값이 결과에 복사되지 않았는가?

통과하면 `proposal_ready`를 반환한다.

```json
{
  "status": "proposal_ready",
  "question": null,
  "skill_id": null,
  "version_id": null,
  "scope_type": "personal",
  "name": "meeting-notes",
  "description": "회의 내용을 정해진 구조로 정리합니다.",
  "skill_markdown": "---\nname: \"meeting-notes\"\ndescription: \"회의 내용을 정해진 구조로 정리합니다.\"\n---\n\n# 작성 절차\n\n- 결정 사항을 구분한다.",
  "issues": []
}
```

응답의 `skill_markdown`은 YAML front matter와 instructions를 합친 사용자 검토용 표현이다. 내부 proposal에는 capability와 Tool이 있지만 HTTP 응답에는 포함하지 않는다.

이 시점에는 다음 row를 만들지 않는다.

- `skills`
- `skill_versions`
- 별도 draft table

`blocked`이면 issue와, 생성 후 차단된 경우 위험 구간을 마스킹한 편집용 proposal을 함께 반환할 수 있다. 사용자가 내용을 직접 수정하면 이전 통과 상태를 재사용하지 않고 다시 검토해야 한다.

## 11. 검토 이후 사용자 동작

### 관리 화면

| 사용자 동작 | 호출 | mode | DB 저장 |
| --- | --- | --- | --- |
| 원문 그대로 검토 | `POST /skills/author` | `preserve` | 없음 |
| AI로 구체화 | `POST /skills/author` | `enhance` | 없음 |
| 보안 재검토 | `POST /skills/author` | `preserve` | 없음 |
| AI로 재생성 | `POST /skills/author` | `regenerate` | 없음 |
| 최종 게시 | `POST /skills/author/publish` | 내부 `preserve` 재검증 | 성공 시 저장 |

관리 화면에 별도 작성 mode selector가 필요하다는 의미는 아니다. `게시`, `AI로 구체화`, `보안 재검토`, `AI로 재생성` 같은 사용자 행동이 내부 mode를 선택한다.

### 채팅 pending proposal

채팅에서 만든 proposal은 DB draft가 아니라 다음 turn의 `conversation_context.pending_skill_proposal`로 전달된다.

```json
{
  "scope_type": "personal",
  "name": "meeting-notes",
  "description": "회의 내용을 정리합니다.",
  "instructions_markdown": "# 작성 절차\n\n- 결정 사항을 구분한다."
}
```

현재 지원하는 후속 동작은 다음과 같다.

| 사용자 표현 | 처리 |
| --- | --- |
| “제목을 weekly-meeting-notes로 바꿔줘” | 이름 형식 검사 후 proposal만 변경, LLM 미호출 |
| “팀 Skill로 변경해줘” | scope만 변경, LLM 미호출 |
| “보안 재검토해줘” | 현재 Markdown을 `preserve`로 공통 재검토 |
| “AI로 재생성해줘” | 현재 Markdown을 `regenerate`로 공통 재검토 |
| “이대로 게시해줘” | 최종 게시 흐름 실행 |

게시 판정은 `publish`나 `post`가 문장 일부에 존재하는지만 보지 않는다. 정해진 긍정 승인 문장 전체가 일치해야 한다.

```text
"이대로 게시해줘"       -> 게시
"please publish it"     -> 게시
"아직 publish 하지 마" -> 게시하지 않음
"do not publish"        -> 게시하지 않음
"post 내용을 수정해줘" -> 게시하지 않음
```

현재 pending proposal의 임의 본문 부분 수정은 별도 후속 동작으로 구현되어 있지 않다. 지원하지 않는 후속 요청은 `400`이며, 전체 Markdown을 다시 검토하거나 AI 재생성을 사용해야 한다.

## 12. 최종 게시와 수정

### 신규 게시 — `POST /skills/author/publish`

```json
{
  "workspace_id": "workspace-id",
  "user_id": "user-id",
  "scope_type": "personal",
  "name": "meeting-notes",
  "description": "회의 내용을 정리합니다.",
  "instructions_markdown": "# 작성 절차\n\n- 결정 사항을 구분한다."
}
```

게시 요청은 이전 proposal의 통과 여부를 신뢰하지 않는다. `AuthorSkillUseCase.execute(..., authoring_mode="preserve")`를 다시 호출해 다음을 재계산한다.

1. 규칙 기반 안전 검사
2. intent classifier와 verifier 합의
3. capability와 Tool
4. 작성 LLM 의미 기반 안전 검사
5. 출력 규칙

검증을 통과하면 `ManageSkillUseCase.create_published()`가 한 transaction에서 Skill과 version 1을 저장한다.

```text
skills.status = enabled
skill_versions.version = 1
skill_versions.status = published
skills.enabled_version_id = version 1
```

DB에 authoring draft를 저장하거나 별도의 draft publish endpoint를 호출하지 않는다.

### 중복 커맨드와 동시성

중복 범위는 다음과 같다.

- personal: 같은 `owner_user_id`
- team: 같은 `workspace_id`

PostgreSQL transaction advisory lock으로 같은 범위·같은 커맨드의 동시 생성을 직렬화한 뒤 중복을 다시 조회한다.

### 기존 Skill 수정 — `PATCH /skills/{skill_id}`

수정 API도 capability와 Tool을 받지 않는다. 전달된 name, description, instructions를 `preserve`로 다시 작성 검토한 뒤 새 published version을 저장한다.

같은 Skill의 동시 수정은 Skill parent row를 `FOR UPDATE`로 잠근 상태에서 다음 version 번호를 다시 계산한다. 수정 전 Skill이 disabled였다면 새 published version을 만들어도 자동 라우팅 상태는 disabled로 유지한다.

### enable과 disable

신규 게시 Skill은 기본 `enabled`다.

- `enabled`: 자연어 auto routing 후보에 포함
- `disabled`: 자연어 auto routing 후보에서 제외
- 명시적 `/command` 또는 `skill_id`: disabled여도 published `enabled_version`이 있으면 실행 가능

현재 삭제 endpoint는 구현되어 있지 않다.

## 13. 완료 AgentRun 기반 Skill 제안

사용자가 “방금 방식대로 Skill로 만들어줘”라고 요청하면 일반 새 Skill 작성이 아니라 완료 작업 일반화 흐름으로 간다.

```text
POST /agent/turn
  -> route.action = skill_draft_proposal
  -> completed source run 확인
  -> ProposeSkillDraftUseCase
  -> 반복 가능한 규칙과 내부 capability·Tool 상한 계산
  -> AuthorSkillUseCase.review_draft
  -> SkillAuthoringResponse
```

직접 API는 `POST /skills/draft-from-runs/preview`다. source run은 `completed` 상태와 성공 operation을 포함해야 한다.

공통 재검토가 계산한 capability나 Tool이 완료 AgentRun proposal의 권한 상한보다 넓어지면 거절한다. 사용자 응답에는 source run ID와 내부 Tool을 포함하지 않는다. 이 결과도 최종 게시 전에는 DB에 저장하지 않는다.

source run의 실제 조회·동일 사용자·Workspace·채팅 권한 검증과 최종 `skill_version_sources` 연결은 Spring Backend의 미구현 범위다.

## 14. 응답과 실패 분기

### 상태

| status | 의미 | 저장 여부 |
| --- | --- | --- |
| `clarification_required` | 채팅에서 작업 종류나 scope 확인 필요 | 저장 안 함 |
| `blocked` | 보안 문제가 있어 수정·재생성 필요 | 저장 안 함 |
| `proposal_ready` | 편집 가능한 검토 제안 준비 | 저장 안 함 |
| `published` | 최종 검증과 transaction 저장 완료 | 저장함 |

### 오류 경계

| 조건 | 현재 결과 |
| --- | --- |
| request schema 형식·길이 위반 | `422` |
| 지원 Agent action이 아닌 요청 | `400` |
| 단발 요청의 intent 모호함·불일치 | `400` |
| 참조 접근 거절·없음 | `400` |
| 고정 템플릿에 문서 여러 개 또는 추출 구조 없음 | `400` |
| capability 없음·Tool 정책 위반 | `400` |
| 커맨드 형식·범위별 중복·관리 권한 오류 | `400` |
| 규칙 또는 의미 기반 보안 문제 | `200` + `status=blocked` |
| LLM HTTP·JSON parse 또는 내부 연동 실패 | `500` |
| 기능 flag로 Skill router 미등록 | `404` |
| Agent service token 미설정 | `503` |
| Agent service token 불일치 | `401` |

HTTP route는 application의 `ValueError`를 현재 일괄 `400`으로 변환한다. 외부 LLM client와 Backend reference reader의 연결·parse 실패는 `RuntimeError`로 전파되어 `500`이 된다.

## 15. 구현 경계와 운영 주의점

### 구현된 범위

- llmPipeline 내부 자연어 작성·보안 검토·이중 intent 판정
- 저장하지 않는 Skill proposal 응답
- 채팅 새 Skill 작성과 제한된 pending 후속 동작
- 완료 AgentRun proposal의 공통 작성 검토
- 최종 게시·수정·enable/disable repository 흐름
- personal 계정/team Workspace 접근과 중복 검사
- 자동 routing과 명시적 slash routing 분리

### 미연결 범위

- Frontend Skill 관리 화면과 채팅 proposal UI
- Spring 공개 Skill proxy API
- Spring의 Skill authoring 전용 참조 문서 read endpoint
- Spring의 completed AgentRun source 조회·권한 검증
- Skill 삭제 API와 조회 화면의 삭제 동작
- 참조 문서 로컬 업로드

`AGENT_SKILLS_ENABLED` 기본값은 `false`다. false이면 `/skills/*`와 `/agent/runs/*` router가 등록되지 않고, `/agent/turn`도 Skill authorer를 구성하지 않는다.

현재 llmPipeline 구현에는 Frontend·Spring Backend 코드나 DB SQL/migration이 포함되어 있지 않다. llmPipeline repository는 `skills`, `skill_versions` 등 필요한 table이 Spring이 관리하는 schema에 존재한다고 가정한다.

단위 테스트는 fake LLM과 in-memory repository를 중심으로 검증한다. 실제 운영 model의 분류 정확도와 classifier/verifier 불일치율은 별도 API·evaluation 실행으로 측정해야 한다.

### 운영상 주의점

- 두 intent 판정은 서로 다른 system prompt를 쓰지만 현재 같은 provider/model 설정을 사용한다.
- fail-closed 정책이므로 실제로 지원 가능한 요청도 두 판정이 다르면 단발 API에서 `400`이 될 수 있다.
- `preserve`는 본문 보존 mode이지 LLM 미사용 mode가 아니다.
- proposal은 DB draft가 아니므로 호출자가 다음 화면이나 채팅 turn에 필요한 표시 값을 유지해야 한다.
- 사용자에게 보여주는 authoring 응답에는 capability와 Tool을 노출하지 않는다.
- 참조 Markdown은 untrusted data이며 선택 문서라는 이유로 내부 지시를 실행하지 않는다.
- 최종 게시에서 전체 검증을 다시 수행하므로 preview 결과만으로 저장 성공을 보장하지 않는다.
- 비활성화는 자동 routing만 끄며 명시적 slash 호출을 삭제하지 않는다.

## 16. 관련 문서

- `docs/backlog/core-data-flow.md`
- `docs/backlog/spec/llmpipeline-backend-api-contract.md`
- `docs/backlog/spec/sdd/agent-skills-and-folder-organization.md`
- `docs/backlog/spec/sdd/tasks/agent-skills-and-folder-organization-tasks.md`
- `docs/backlog/issue/backend/2026-08-04.md`
- `docs/backlog/issue/frontend/2026-08-04.md`
- `docs/backlog/llm-wiki/flows/markdown-editor.md`
