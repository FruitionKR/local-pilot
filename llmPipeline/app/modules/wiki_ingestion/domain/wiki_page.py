import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime


class WikiPageNotFoundError(LookupError):
    pass


class WikiPageSlugConflictError(Exception):
    pass


@dataclass(frozen=True)
class WikiPageRenameResult:
    id: str
    page_type: str
    title: str
    previous_title: str
    slug: str
    previous_slug: str
    slug_updated: bool
    updated_at: datetime


def validate_wiki_page_title(title: str) -> str:
    normalized = title.strip()
    if not 1 <= len(normalized) <= 255:
        raise ValueError("Wiki page title must contain 1-255 characters.")
    return normalized


def slugify_wiki_page_title(title: str) -> str:
    normalized = unicodedata.normalize("NFKC", title).lower()
    normalized = re.sub(r"\s+", "-", normalized)
    normalized = re.sub(r"[^a-z0-9가-힣-]", "", normalized)
    normalized = re.sub(r"-+", "-", normalized).strip("-")
    return normalized or "untitled"
