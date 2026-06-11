---
type: source
document_id: codex-test-llm-wiki-api-20260611_012854
source_file: runs\_api_inputs\da8956d9-7253-4a4c-8920-9c1c5d979a29\codex-test-llm-wiki-api-20260611_012854.md
---

# LLM Wiki

## Summary
LLM Wiki는 LLM을 활용해 지속적으로 업데이트되는 개인 지식 베이스를 구축하는 패턴입니다. 기존 RAG 방식과 달리, 위키를 지속적으로 구축하고 유지하여 지식을 축적하며, 인간이 소싱과 탐색을 담당하고 LLM이 위키 유지보수를 담당합니다. 아키텍처, 운영 방식, 인덱싱, 도구 통합 등을 설명하며, 다양한 활용 사례와 팁을 제공합니다.

## Key Points
- LLM Wiki는 RAG와 달리 지속적인 위키 구축을 통해 지식을 축적합니다. [B0005, B0006, B0007]
- 위키는 LLM이 관리하며, 인간은 소싱과 탐색을 담당합니다. [B0008, B0030]
- 아키텍처는 Raw sources, 위키, 스키마 3계층으로 구성됩니다. [B0013, B0014, B0015]
- 운영 방식은 Ingest, Query, Lint 세 가지로 구분됩니다. [B0017, B0018, B0019]
- 인덱스와 로그 파일을 통해 위키를 효율적으로 관리합니다. [B0022, B0023]
- Obsidian, Marp, Dataview 등의 도구와 통합 가능합니다. [B0027, B0028]

## Extracted Concepts
- [[persistent-wiki|Persistent Wiki]] [B0006, B0007, B0029]
- [[llm-wiki|LLM Wiki]] [B0001, B0002, B0006]
- [[wiki-architecture|Wiki Architecture]] [B0013, B0014, B0015]
- [[wiki-operations|Wiki Operations]] [B0017, B0018, B0019]
- [[wiki-indexing-logging|Wiki Indexing and Logging]] [B0022, B0023]
