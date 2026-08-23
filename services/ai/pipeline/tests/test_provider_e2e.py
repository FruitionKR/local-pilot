import json
from pathlib import Path

from provider_e2e import run_provider_e2e


class _Client:
    def __init__(self) -> None:
        self.system_prompts: list[str] = []

    def complete_json(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        trusted_identifiers: tuple[str, ...] = (),
    ) -> dict[str, object]:
        self.system_prompts.append(system_prompt)
        if "Stage=ChunkSemanticExtraction" in system_prompt:
            return {
                "chunk_id": "provider-e2e",
                "semantic_summary": "RAG 요약",
                "key_points": [],
                "observations": [],
                "categories": [],
                "core_concepts": [],
                "section_candidates": [],
                "mentions": [],
                "evidence_claims": [],
                "needs_neighbor_context": False,
                "context_problem": None,
            }
        if "query and search specialist" in system_prompt:
            return {
                "action": "markdown_edit",
                "retrieval_source": "none",
                "reason": "활성 문서 편집 요청",
                "message": None,
            }
        if "conversation specialist" in system_prompt:
            return {
                "action": "chat_answer",
                "reason": "검색이 필요한 질문",
                "message": None,
            }
        if "Markdown edit engine" in system_prompt:
            return {
                "decision": "chat_answer",
                "reason": "설명 요청",
                "message": None,
            }
        if "Markdown document creation engine" in system_prompt:
            payload = json.loads(user_prompt)
            if payload.get("specialist_mode"):
                return {
                    "decision": "chat_answer",
                    "reason": "설명 요청",
                    "message": None,
                }
            return {
                "title": "RAG 합의",
                "summary": "RAG 합의를 정리했습니다.",
                "markdown": "# RAG 합의\n\nRAG는 검색 근거를 사용합니다.",
            }
        if "action" in system_prompt and "chat_answer" in system_prompt:
            payload = json.loads(user_prompt)
            if payload["has_selected_completed_work"]:
                return {
                    "action": "skill_draft_proposal",
                    "confidence": 1.0,
                    "retrieval_source": "none",
                    "document_operation": "none",
                    "persist": False,
                    "required_capabilities": [],
                    "reason": "완료 작업 일반화",
                    "edit_goal": None,
                    "edit_operation": None,
                    "edit_destination": None,
                }
            if payload["message"].startswith("방금 완료한 작업"):
                return {
                    "action": "skill_draft_proposal",
                    "confidence": 1.0,
                    "retrieval_source": "none",
                    "document_operation": "none",
                    "persist": False,
                    "required_capabilities": [],
                    "reason": "완료 작업 일반화",
                    "edit_goal": None,
                    "edit_operation": None,
                    "edit_destination": None,
                }
            if payload["message"].startswith("현재 문서를 요약"):
                return {
                    "action": "workspace_workflow",
                    "confidence": 1.0,
                    "retrieval_source": "none",
                    "document_operation": "edit",
                    "persist": True,
                    "required_capabilities": (
                        ["document-edit", "folder-organize"]
                        if "보관 폴더" in payload["message"]
                        else ["document-edit"]
                    ),
                    "reason": "문서 요약과 이동",
                    "edit_goal": "shorten",
                    "edit_operation": "replace",
                    "edit_destination": "target",
                }
            if payload["message"].startswith("Wiki 근거"):
                return {
                    "action": "markdown_edit",
                    "confidence": 1.0,
                    "retrieval_source": "workspace",
                    "document_operation": "edit",
                    "persist": False,
                    "required_capabilities": ["document-edit"],
                    "reason": "근거 기반 보완",
                    "edit_goal": "other",
                    "edit_operation": "replace",
                    "edit_destination": "target",
                }
            return {
                "action": "chat_answer",
                "confidence": 1.0,
                "retrieval_source": "workspace",
                "document_operation": "none",
                "persist": False,
                "required_capabilities": [],
                "reason": "질문 응답",
                "edit_goal": None,
                "edit_operation": None,
                "edit_destination": None,
            }
        return {
            "title": "RAG 합의",
            "summary": "RAG 합의를 정리했습니다.",
            "markdown": "# RAG 합의\n\nRAG는 검색 근거를 사용합니다.",
        }


class _FailingClient(_Client):
    def complete_json(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        trusted_identifiers: tuple[str, ...] = (),
    ) -> dict[str, object]:
        if "Stage=ChunkSemanticExtraction" in system_prompt:
            raise RuntimeError(
                "LLM API HTTP 403: provider response with secret detail"
            )
        return super().complete_json(
            system_prompt,
            user_prompt,
            trusted_identifiers=trusted_identifiers,
        )


def test_runs_ingestion_agent_and_markdown_contracts() -> None:
    results = run_provider_e2e(
        _Client(),  # type: ignore[arg-type]
        prompt_root=Path(__file__).parents[1] / "prompts",
    )

    assert [result["name"] for result in results] == [
        "ingestion_json",
        "agent_router",
        "agent_specialist_handoffs",
        "markdown_create",
    ]
    assert all(result["passed"] for result in results)


def test_uses_markdown_create_prompt() -> None:
    client = _Client()

    run_provider_e2e(
        client,  # type: ignore[arg-type]
        prompt_root=Path(__file__).parents[1] / "prompts",
    )

    assert any(
        "Markdown document creation engine" in prompt
        for prompt in client.system_prompts
    )


def test_records_safe_failure_and_continues_other_probes() -> None:
    results = run_provider_e2e(
        _FailingClient(),  # type: ignore[arg-type]
        prompt_root=Path(__file__).parents[1] / "prompts",
    )

    assert results[0] == {
        "name": "ingestion_json",
        "passed": False,
        "elapsed_seconds": results[0]["elapsed_seconds"],
        "error_type": "RuntimeError",
        "http_status": 403,
    }
    assert "secret" not in str(results[0])
    assert results[1]["passed"] is True
    assert results[2]["passed"] is True
    assert results[3]["passed"] is True
