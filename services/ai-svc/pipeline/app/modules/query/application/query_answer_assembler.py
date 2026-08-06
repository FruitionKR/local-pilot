import re
from dataclasses import replace

from app.modules.query.application.extract_answer_citations import ExtractAnswerCitationsUseCase
from app.modules.query.application.ports import AnswerGeneratorPort
from app.modules.query.domain.entities import EvidenceSnippet, GeneratedAnswer, QueryContext


class QueryAnswerAssembler:
    def __init__(
        self,
        answer_generator: AnswerGeneratorPort,
        extract_answer_citations: ExtractAnswerCitationsUseCase | None = None,
    ) -> None:
        self._answer_generator = answer_generator
        self._extract_answer_citations = extract_answer_citations or ExtractAnswerCitationsUseCase()

    def generate_supported_answer(self, query_context: QueryContext) -> tuple[GeneratedAnswer, list[EvidenceSnippet]]:
        answer = self._answer_generator.generate_answer(query_context)
        answer = GeneratedAnswer(
            content=self._extract_answer_citations.ensure_sentence_citations(
                answer.content,
                query_context.evidence_snippets[0].rank if query_context.evidence_snippets else None,
            )
        )
        return self.renumber_used_evidence(answer, query_context.evidence_snippets)

    def renumber_used_evidence(
        self,
        answer: GeneratedAnswer,
        evidence_snippets: list[EvidenceSnippet],
    ) -> tuple[GeneratedAnswer, list[EvidenceSnippet]]:
        if not evidence_snippets:
            return answer, evidence_snippets

        snippets_by_rank = {snippet.rank: snippet for snippet in evidence_snippets}
        old_to_new_rank: dict[int, int] = {}

        def next_rank(old_rank: int) -> int:
            if old_rank not in old_to_new_rank:
                old_to_new_rank[old_rank] = len(old_to_new_rank) + 1
            return old_to_new_rank[old_rank]

        def replace_marker(match: re.Match[str]) -> str:
            ranks = [int(value) for value in re.findall(r"\d+", match.group(1))]
            remapped = [
                str(next_rank(rank)) if rank in snippets_by_rank else str(rank)
                for rank in ranks
            ]
            return f"[{', '.join(remapped)}]"

        content = re.sub(r"\[((?:\d+)(?:\s*,\s*\d+)*)\]", replace_marker, answer.content)
        used_snippets = [
            replace(snippets_by_rank[old_rank], rank=new_rank)
            for old_rank, new_rank in sorted(old_to_new_rank.items(), key=lambda item: item[1])
            if old_rank in snippets_by_rank
        ]
        if not used_snippets:
            return answer, []
        return GeneratedAnswer(content=content), used_snippets
