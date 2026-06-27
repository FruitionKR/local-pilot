from __future__ import annotations

from typing import Any, Protocol, Sequence

from app.modules.wiki_generation.domain.entities import SemanticPacket, SourceBlock


JsonDict = dict[str, Any]


class SemanticExtractor(Protocol):
    def extract(self, packet: SemanticPacket) -> JsonDict:
        ...


class ConceptPageGenerator(Protocol):
    def generate(self, concept: JsonDict, evidence_units: list[JsonDict], source_blocks: Sequence[SourceBlock]) -> JsonDict:
        ...


class ConceptResolver(Protocol):
    def resolve(
        self,
        incoming_concepts: list[JsonDict],
        existing_concepts: list[JsonDict],
        missing_related_hints: list[JsonDict] | None = None,
    ) -> JsonDict:
        ...


class SectionPolisher(Protocol):
    def polish(self, payload: JsonDict, source_blocks: Sequence[SourceBlock]) -> JsonDict:
        ...
