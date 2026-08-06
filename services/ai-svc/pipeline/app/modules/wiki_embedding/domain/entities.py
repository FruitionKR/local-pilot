from dataclasses import dataclass


@dataclass(frozen=True)
class WikiPageEmbeddingTarget:
    page_id: str
    title: str
    summary: str
    markdown_uri: str


@dataclass(frozen=True)
class WikiPageEmbeddingInput:
    page_id: str
    representation: str
    representation_hash: str

