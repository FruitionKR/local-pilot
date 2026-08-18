from __future__ import annotations

import argparse
import json
import re
import time
from collections.abc import Callable
from pathlib import Path

from app.core.llm_env import (
    SUPPORTED_LLM_PROVIDERS,
    resolve_llm_selection,
    resolve_llm_provider_defaults,
)
from app.modules.agent.domain.entities import AgentTurnRequest
from app.modules.agent.infrastructure.chat_completions_turn_router import (
    DEFAULT_AGENT_TURN_ROUTER_PROMPT,
    ChatCompletionsTurnRouter,
)
from app.modules.markdown_edit.domain.entities import MarkdownCreateRequest
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import (
    DEFAULT_MARKDOWN_CREATE_PROMPT,
    DEFAULT_MARKDOWN_EDIT_PROMPT,
    DEFAULT_MARKDOWN_SOURCE_EDIT_PROMPT,
    ChatCompletionsMarkdownEditor,
)
from app.modules.wiki_generation.domain.entities import SemanticPacket
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
    GenericChatCompletionsExtractor,
)


REQUIRED_EXTRACTION_KEYS = {
    "chunk_id",
    "semantic_summary",
    "key_points",
    "observations",
    "categories",
    "core_concepts",
    "section_candidates",
    "mentions",
    "evidence_claims",
    "needs_neighbor_context",
    "context_problem",
}

def run_provider_e2e(
    client: ChatCompletionsJsonClient,
    *,
    prompt_root: Path,
) -> list[dict[str, object]]:
    return [
        _run_probe(
            "ingestion_json",
            lambda: _probe_ingestion(client, prompt_root),
        ),
        _run_probe(
            "agent_router",
            lambda: _probe_agent_router(client),
        ),
        _run_probe(
            "markdown_create",
            lambda: _probe_markdown_create(client),
        ),
    ]


def _probe_ingestion(
    client: ChatCompletionsJsonClient,
    prompt_root: Path,
) -> None:
    extraction = GenericChatCompletionsExtractor(
        client,
        (prompt_root / "semantic_extraction.system.md").read_text(
            encoding="utf-8"
        ),
    ).extract(
        SemanticPacket(
            chunk_id="provider-e2e",
            document_id="provider-e2e",
            block_ids=["B0001"],
            text="[B0001] RAG는 검색한 근거를 사용해 답변을 생성한다.",
        )
    )
    missing_keys = sorted(REQUIRED_EXTRACTION_KEYS - extraction.keys())
    if missing_keys:
        raise RuntimeError(
            f"Ingestion JSON contract missing keys: {', '.join(missing_keys)}"
        )


def _probe_agent_router(client: ChatCompletionsJsonClient) -> None:
    route = ChatCompletionsTurnRouter(
        client,
        Path(DEFAULT_AGENT_TURN_ROUTER_PROMPT).read_text(encoding="utf-8"),
    ).route(AgentTurnRequest(message="RAG가 무엇인지 한 문장으로 설명해줘."))
    if route.action != "chat_answer":
        raise RuntimeError(
            f"Agent router contract expected chat_answer, got {route.action}"
        )


def _probe_markdown_create(client: ChatCompletionsJsonClient) -> None:
    document = ChatCompletionsMarkdownEditor(
        client,
        Path(DEFAULT_MARKDOWN_EDIT_PROMPT).read_text(encoding="utf-8"),
        create_system_prompt=Path(DEFAULT_MARKDOWN_CREATE_PROMPT).read_text(
            encoding="utf-8"
        ),
        source_edit_system_prompt=Path(
            DEFAULT_MARKDOWN_SOURCE_EDIT_PROMPT
        ).read_text(encoding="utf-8"),
    ).generate_markdown(
        MarkdownCreateRequest(
            instruction="합의 내용을 Markdown 문서로 만들어줘.",
            conversation_summary=(
                "RAG는 검색한 근거를 사용해 답변을 생성한다."
            ),
        )
    ).document
    if not document.title or not document.summary or not document.markdown:
        raise RuntimeError("Markdown create contract returned an empty field")


def _run_probe(
    name: str,
    probe: Callable[[], None],
) -> dict[str, object]:
    started_at = time.perf_counter()
    try:
        probe()
    except Exception as error:
        return {
            "name": name,
            "passed": False,
            "elapsed_seconds": round(
                time.perf_counter() - started_at,
                2,
            ),
            "error_type": type(error).__name__,
            "http_status": _http_status(error),
        }
    return {
        "name": name,
        "passed": True,
        "elapsed_seconds": round(time.perf_counter() - started_at, 2),
    }


def _http_status(error: Exception) -> int | None:
    match = re.search(r"LLM API HTTP (\d{3})", str(error))
    return int(match.group(1)) if match else None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Provider별 ingestion·Agent·Markdown 실제 API smoke 검증"
    )
    parser.add_argument(
        "--provider",
        choices=SUPPORTED_LLM_PROVIDERS,
        required=True,
    )
    parser.add_argument("--model", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument(
        "--prompt-root",
        default=str(Path(__file__).parent / "prompts"),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    provider, model = resolve_llm_selection(args.provider, args.model)
    defaults = resolve_llm_provider_defaults(
        provider=provider,
        model=model,
    )
    if not defaults.api_key:
        raise SystemExit(
            f"Missing API key. Set {defaults.api_key_env} before provider E2E."
        )
    client = ChatCompletionsJsonClient(
        ChatClientConfig(
            api_key=defaults.api_key,
            model=defaults.model or args.model,
            temperature=None,
            timeout_seconds=args.timeout_seconds,
            json_mode=True,
            provider=defaults.provider,
        )
    )
    started_at = time.perf_counter()
    results = run_provider_e2e(
        client,
        prompt_root=Path(args.prompt_root),
    )
    passed = sum(bool(result["passed"]) for result in results)
    print(
        json.dumps(
            {
                "provider": defaults.provider,
                "model": defaults.model,
                "passed": passed,
                "total": len(results),
                "elapsed_seconds": round(
                    time.perf_counter() - started_at,
                    2,
                ),
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    if passed != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
