import re


class ExtractAnswerCitationsUseCase:
    def ensure_sentence_citations(self, answer: str, fallback_rank: int | None) -> str:
        if fallback_rank is None:
            return answer
        sentences = self._split_sentences(answer)
        if not sentences:
            return answer
        cited_sentences = []
        for sentence in sentences:
            if re.search(r"\[\d+(?:\s*,\s*\d+)*\]", sentence):
                cited_sentences.append(sentence)
            else:
                cited_sentences.append(f"{sentence} [{fallback_rank}]")
        return " ".join(cited_sentences)

    def _split_sentences(self, text: str) -> list[str]:
        normalized = " ".join(line.strip() for line in text.strip().splitlines() if line.strip())
        if not normalized:
            return []
        normalized = re.sub(r"(?<=[A-Za-z0-9가-힣])\.(?=[A-Za-z0-9가-힣])", "<DOT>", normalized)
        pattern = r"[^.!?。！？]+[.!?。！？](?:\s*\[\d+(?:\s*,\s*\d+)*\])*"
        matches = list(re.finditer(pattern, normalized))
        sentences = [match.group(0).replace("<DOT>", ".").strip() for match in matches]
        last_end = matches[-1].end() if matches else 0
        if last_end < len(normalized.strip()):
            remainder = normalized[last_end:].replace("<DOT>", ".").strip()
            if remainder:
                sentences.append(remainder)
        return sentences or [normalized.replace("<DOT>", ".")]
