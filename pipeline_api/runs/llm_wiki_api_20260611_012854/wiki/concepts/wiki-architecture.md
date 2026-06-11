---
type: concept
slug: wiki-architecture
sources: codex-test-llm-wiki-api-20260611_012854
mention_count: 3
importance_score: 9.55
generated_by: llm_concept_page_generation
confidence: 0.9
---

# Wiki Architecture

## Definition
Raw sources, 위키, 스키마로 구성된 3계층 구조로, LLM 기반 위키의 기본 설계입니다. [B0013, B0014, B0015]

## Key Points
- Raw sources는 변경 불가능한 원본 문서(기사, 논문, 데이터 파일 등)로 구성된 계층입니다. [B0013]
- 위키 계층은 LLM이 생성한 마크다운 파일(요약, 엔티티 페이지, 개념 페이지 등)로 구성되며, LLM이 완전히 관리합니다. [B0014]
- 스키마는 위키 구조, 규칙, 워크플로우를 정의하는 구성 파일로, LLM과 사용자가 공동으로 발전시킵니다. [B0015]

## Evidence
- LLM Wiki는 Raw sources, 위키, 스키마 3계층으로 구성됩니다. [B0013, B0014, B0015]

## Related Concepts
- [[raw-sources|Raw sources]]
- [[llm-generated-wiki|LLM-generated wiki]]
- [[schema-configuration|Schema configuration]]
