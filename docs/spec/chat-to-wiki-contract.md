# 채팅 Wiki page화 계약

이 문서는 저장된 채팅을 나중에 다시 검색 가능한 Wiki page로 만들기 위한 backend와 `llmPipeline` 사이의 계약을 정리한다.

일반 채팅 답변 계약은 [query-engine.md](./query-engine.md)를 따른다. 이 문서는 `/query` 응답을 설명하는 문서가 아니라, **이미 저장된 채팅 대화를 원문 문서처럼 정리해서 기존 Wiki page 생성 pipeline에 넣는 흐름**을 설명한다.

## 1. 목표

사용자가 채팅에서 논의한 내용은 저장만 해두면 나중에 Wiki graph 검색 대상이 되지 않는다. Wiki page화는 저장된 채팅을 Markdown/text 원문으로 정리하고, 그 원문을 기존 문서 ingestion과 같은 pipeline에 넣어 `source page`, `concept page`, `source_blocks`, page link를 생성하는 과정이다.

```text
채팅 진행
  -> Spring backend가 user/assistant 메시지 저장
  -> 사용자가 저장된 채팅의 Wiki page화를 요청하거나 backend 정책이 트리거
  -> backend가 채팅을 Markdown/text 원문 문서로 정리
  -> 기존 Wiki page 생성 pipeline 실행
  -> source page, concept page, source_blocks, links 저장
  -> 이후 /query 검색 대상에 포함
```

## 2. 현재 구현 기준

현재 `llmPipeline`에는 채팅 전용 source page 생성 API가 따로 없다. 채팅 Wiki page화는 다음 기존 기능을 재사용한다.

- `MarkdownBlockExtractor.extract_text(...)`: Markdown/text를 `SourceDocument`와 `SourceBlock[]`로 분해
- `SemanticPacketBuilder`: block 목록을 LLM 입력 packet으로 분할
- semantic extraction, normalize, concept resolution
- `SourcePageAssembler`: source page Markdown 생성
- concept page assembler/generator
- wiki ingestion repository: `documents`, `source_blocks`, `wiki_pages`, `document_wiki_links`, `wiki_page_links` 저장

따라서 backend가 해야 할 핵심 일은 “채팅을 pipeline이 읽을 수 있는 안정적인 원문 Markdown/text로 만드는 것”이다.

## 3. Backend 책임

Spring backend는 다음을 소유한다.

- 채팅 메시지 저장과 조회
- conversation, workspace, user 권한 검증
- 어떤 채팅 범위를 Wiki page화할지 결정
- 저장된 메시지를 Markdown/text 원문으로 직렬화
- 원문 문서 id와 content hash 관리
- pipeline 실행 요청 또는 queue 등록
- pipeline 결과와 기존 documents/wiki tables 연결
- 중복 실행, 재시도, 실패 상태 관리

`llmPipeline`은 채팅 저장소를 직접 읽지 않는다. backend가 원문 텍스트와 필요한 식별자를 넘겨야 한다.

## 4. llmPipeline 책임

`llmPipeline`은 전달받은 채팅 Markdown/text를 일반 문서처럼 처리한다.

- 원문을 block으로 나눈다.
- block마다 `B0001` 같은 짧은 anchor id를 부여한다.
- 의미 추출 단계에서 요약, 핵심 주장, concept 후보, evidence를 만든다.
- source page를 생성한다.
- 반복되거나 재사용할 만한 개념은 concept page 후보로 만든다.
- source page와 concept page 사이 link를 만든다.
- 저장 단계가 연결된 실행에서는 `source_blocks`, `wiki_pages`, `document_wiki_links`, `wiki_page_links`를 적재한다.

`llmPipeline`은 사용자의 채팅 권한, conversation 보존 정책, 공개 범위 정책을 판단하지 않는다.

## 5. 입력 계약

현재 실행 단위는 “하나의 채팅 export를 하나의 원문 문서처럼 전달한다”이다. 구현 방식은 HTTP endpoint, queue worker, CLI 실행 중 무엇이든 될 수 있지만, pipeline에 들어가기 전 최소 정보는 아래와 같아야 한다.

```json
{
  "source_document_id": "chatdoc_workspace-1_conversation-7_20260701",
  "source_path": "chat://workspace-1/conversations/conversation-7/wiki-export/20260701",
  "title": "LangSmith 설정 논의",
  "markdown": "# LangSmith 설정 논의\n\n## 대화 정보\n\n- workspace_id: workspace-1\n- conversation_id: conversation-7\n\n## 대화 내용\n\n### 2026-07-01 10:00 User\nLangSmith 연결은 어디서 봐?\n\n### 2026-07-01 10:01 Assistant\nLangSmith 프로젝트의 traces 화면에서 run을 확인합니다.\n",
  "metadata": {
    "workspace_id": "workspace-1",
    "conversation_id": "conversation-7",
    "message_ids": ["chat_user_1", "chat_assistant_1"],
    "created_from": "chat_export"
  }
}
```

필드 의미:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `source_document_id` | 예 | backend가 부여하는 채팅 export 문서 id. pipeline 실행 시 `document.document_id`로 사용 |
| `source_path` | 예 | 원문 출처를 식별하는 논리 경로. 실제 파일 경로일 필요는 없음 |
| `title` | 예 | source page 제목 후보. Markdown 첫 heading에도 같은 제목을 넣는 것을 권장 |
| `markdown` | 예 | 저장된 채팅을 직렬화한 원문 |
| `metadata.workspace_id` | 예 | 권한과 검색 scope 연결용 |
| `metadata.conversation_id` | 예 | 원본 conversation 추적용 |
| `metadata.message_ids` | 예 | 이 export에 포함된 message id 목록 |
| `metadata.created_from` | 권장 | `chat_export` 같은 출처 구분값 |

현재 `MarkdownBlockExtractor`는 텍스트의 SHA-1로 기본 `document_id`를 만들지만, backend가 안정적인 id를 유지해야 하므로 pipeline 실행 시 `source_document_id`를 명시해서 덮어써야 한다.

## 6. Markdown 직렬화 규칙

채팅은 사람이 읽기 좋고 pipeline이 block으로 안정적으로 나눌 수 있는 Markdown으로 만든다.

권장 구조:

```markdown
# {채팅 문서 제목}

## 대화 정보

- workspace_id: workspace-1
- conversation_id: conversation-7
- exported_at: 2026-07-01T10:30:00Z

## 대화 내용

### 2026-07-01 10:00 User

LangSmith 연결은 어디서 봐?

### 2026-07-01 10:01 Assistant

LangSmith 프로젝트의 traces 화면에서 run을 확인합니다.
```

규칙:

- user/assistant 발화를 모두 남긴다.
- 메시지 순서를 보존한다.
- 타임스탬프는 가능하면 ISO-8601 또는 표시용 고정 포맷으로 넣는다.
- message id는 필요하면 heading 아래 bullet metadata로 넣되, 본문 의미를 해치지 않게 한다.
- 한 메시지는 최소 하나의 문단 block이 되도록 빈 줄로 분리한다.
- LLM이 추론한 요약만 넣지 말고, 원문 발화를 함께 포함한다.
- 비밀값, credential, private URL 등 저장하면 안 되는 값은 backend 정책으로 마스킹한 뒤 넘긴다.

## 7. Block과 evidence 추적

pipeline은 Markdown/text를 block으로 나누고 각 block에 `B0001`, `B0002` 같은 id를 붙인다.

`SourceBlock` 주요 필드:

| 필드 | 설명 |
| --- | --- |
| `document_id` | `source_document_id`와 같아야 함 |
| `block_id` | `B0001` 형식의 짧은 block anchor |
| `source_reference_id` | DB/export에서 쓸 수 있는 안정 참조 id |
| `text` | block 본문 |
| `line_start`, `line_end` | Markdown 원문 기준 line 범위 |
| `section_path` | heading 기준 섹션 경로 |
| `block_type` | `heading`, `paragraph`, `list`, `code` 등 |

나중에 `/query` 답변은 `evidence_snippets[].source_document_id`와 `source_block_ids[]`로 이 block을 다시 참조한다. 따라서 채팅 export 원문과 block id 매핑은 재처리나 디버깅을 위해 보존되어야 한다.

## 8. 출력 계약

채팅 Wiki page화가 끝나면 일반 문서 ingestion과 같은 종류의 결과가 생긴다.

| 결과 | 설명 |
| --- | --- |
| `documents` | 채팅 export 원문 문서 레코드 |
| `source_blocks` | 채팅 Markdown을 나눈 block 목록 |
| `wiki_pages` source | 채팅 전체를 대표하는 source page |
| `wiki_pages` concept | 채팅에서 재사용할 만한 개념 page |
| `document_wiki_links` | 채팅 export 문서와 생성된 wiki page 연결 |
| `wiki_page_links` | source page와 concept page, concept 간 link |

source page는 “대화 내용을 대표하는 원문 기반 page”다. concept page는 대화에서 반복되거나 이후 검색에 재사용할 만한 개념이 있을 때 생성된다.

현재 pipeline은 채팅 전용 고정 필드인 “결정된 사항”, “남은 질문”, “검증 결과” 같은 항목을 항상 추출하도록 계약되어 있지 않다. 저장된 채팅 Markdown/text를 일반 문서로 보고, 기존 semantic extraction과 normalize 단계가 source page 내용, evidence, concept 후보를 만든다. 그런 고정 섹션이 필요하면 backend가 Markdown export 단계에서 명시적으로 섹션을 만들거나, 별도 chat-specific extraction 기능을 추가해야 한다.

## 9. 상태 관리

채팅 Wiki page화는 비동기 작업으로 다루는 것이 안전하다.

권장 상태:

| 상태 | 의미 |
| --- | --- |
| `pending` | export 요청이 생성되었지만 pipeline이 아직 시작되지 않음 |
| `processing` | pipeline 실행 중 |
| `completed` | source/concept page와 block 저장 완료 |
| `failed` | pipeline 실패. 재시도 가능 |
| `skipped` | 중복 export 또는 정책상 처리하지 않음 |

backend는 같은 conversation 범위를 같은 내용으로 반복 export하지 않도록 content hash 또는 `(conversation_id, message range, content_hash)` 기준 중복 방지를 해야 한다.

## 10. API/Queue 설계 기준

현재 repository 기준으로 이 문서가 요구하는 별도 Spring API나 `llmPipeline` HTTP endpoint가 이미 존재한다고 가정하지 않는다. 구현 시에는 다음 중 하나로 연결한다.

1. Spring backend가 채팅 export를 문서 업로드와 같은 흐름으로 저장하고 기존 document processing queue에 등록한다.
2. Spring backend가 내부 worker에서 `llmPipeline` 실행을 요청한다.
3. 운영 도구나 batch job이 저장된 채팅을 Markdown으로 export한 뒤 pipeline을 실행한다.

어떤 방식을 쓰든 pipeline 입력은 5장의 입력 계약을 만족해야 한다.

## 11. `/agent/turn`과의 관계

`/agent/turn`의 `markdown_create`는 “대화를 바탕으로 새 Markdown draft를 만드는 기능”이다. 이것은 채팅 Wiki page화와 다르다.

| 기능 | 목적 | 결과 |
| --- | --- | --- |
| `/agent/turn` `markdown_create` | 사용자가 볼 새 Markdown 초안 생성 | editor draft |
| 채팅 Wiki page화 | 저장된 채팅을 검색 가능한 Wiki graph에 편입 | source page, concept page, source_blocks |

사용자가 “지금까지 대화를 문서로 만들어줘”라고 요청하면 먼저 `markdown_create`로 editor draft를 만들 수 있다. 반대로 “이 채팅을 지식으로 저장” 같은 기능은 저장된 채팅 원문을 export해서 이 문서의 흐름으로 pipeline에 넣어야 한다.

## 12. 구현 계획 체크리스트

AI agent나 backend/frontend 작업자가 계획을 세울 때는 아래 항목을 빠뜨리지 않는다.

1. 채팅 범위 결정: conversation 전체인지, 선택 메시지인지, 특정 시점 이후인지 정한다.
2. 권한 확인: export 대상 메시지에 대한 workspace/user 접근 권한을 확인한다.
3. Markdown export: user/assistant 발화, 순서, 시간, message id를 보존해 Markdown을 만든다.
4. 비밀값 마스킹: credential, token, private key, 민감 URL을 저장 전에 제거하거나 마스킹한다.
5. 안정 id 부여: `source_document_id`, `source_path`, content hash를 만든다.
6. pipeline 실행: 기존 Wiki page 생성 pipeline에 Markdown/text를 전달한다.
7. block 저장 확인: `source_blocks`가 `source_document_id`와 함께 저장되는지 확인한다.
8. page/link 저장 확인: source page, concept page, document link, page link가 저장되는지 확인한다.
9. query 포함 확인: 완료 후 `/query`에서 해당 source/concept page가 검색 후보에 포함되는지 확인한다.
10. 재처리 정책: 같은 채팅을 다시 export할 때 새 문서로 만들지, 기존 문서를 갱신할지 정책을 정한다.

## 13. 현재 제한

- 채팅 전용 source page schema는 아직 없다.
- 채팅 export 전용 `llmPipeline` HTTP endpoint는 아직 없다.
- 채팅 message id와 `source_blocks` 사이의 별도 DB mapping table은 현재 명시되어 있지 않다.
- 재처리 시 기존 source/concept page를 어떻게 갱신할지는 별도 정책이 필요하다.
- schema 문서의 prompt 설정과는 별개 기능이며, 이 문서에서는 Wiki Schema 계약을 다루지 않는다.
