from __future__ import annotations

from app.modules.document_restoration.domain.text_quality import (
    language_score,
    looks_glyph_encoded,
)


def encode_shifted_ascii(text: str) -> str:
    encoded = []
    for char in text:
        code = ord(char)
        encoded.append(chr(code - 29) if 29 <= code <= 122 else char)
    return "".join(encoded)


def test_shift_encoded_sentence_is_detected_without_token_dictionary() -> None:
    source = "The proposed method is evaluated with the original design."
    encoded = encode_shifted_ascii(source)

    assert looks_glyph_encoded(encoded)
    assert language_score(source) > language_score(encoded)


def test_normal_technical_sentence_is_not_detected() -> None:
    source = "Finite element analysis uses a three dimensional electromagnetic model."

    assert not looks_glyph_encoded(source)


def test_shift_encoded_caption_is_detected_without_common_words() -> None:
    source = "Finite element model mesh generation."
    encoded = encode_shifted_ascii(source)

    assert looks_glyph_encoded(encoded)


def test_short_equation_token_is_not_treated_as_encoded_text() -> None:
    assert not looks_glyph_encoded("SNR")
