from app.modules.skill.domain.safety import inspect_skill_instructions


def test_detects_all_personal_data_and_credentials() -> None:
    value = (
        "이메일 user@example.com, 전화 010-1234-5678, 주민번호 900101-1234568, "
        "카드 4111 1111 1111 1111, 계좌번호 123-456-789012\n"
        "API_KEY=super-secret-token\n"
        "ghp_000000000000000000000000000000"
    )

    issues = inspect_skill_instructions(value)

    assert [issue.category for issue in issues] == [
        "personal_email",
        "personal_phone",
        "resident_registration_number",
        "payment_card",
        "bank_account",
        "credential",
        "credential",
    ]
    assert all(issue.start is not None and issue.end is not None for issue in issues)


def test_detects_each_credential_instead_of_only_the_first() -> None:
    value = (
        "-----BEGIN PRIVATE KEY-----\nfake-key-body\n-----END PRIVATE KEY-----\n"
        "Bearer 000000000000000000000000000000\n"
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signaturevalue\n"
        "password=super-secret-password"
    )

    issues = inspect_skill_instructions(value)

    assert [issue.category for issue in issues] == ["credential"] * 4


def test_allows_empty_and_underscore_personal_data_templates() -> None:
    value = (
        "name: meeting-summary\n"
        "이름:\n"
        "주소: __________\n\n"
        "| 이름 | 연락처 |\n"
        "| --- | --- |\n"
        "| ____ | __________ |"
    )

    assert inspect_skill_instructions(value) == ()


def test_blocks_personal_data_field_values_and_placeholders() -> None:
    value = (
        "이름: 홍길동\n"
        "주소: [주소]\n\n"
        "1. 성명: 김철수\n\n"
        "| 이름 | 연락처 |\n"
        "| --- | --- |\n"
        "| [이름] | [연락처] |"
    )

    issues = inspect_skill_instructions(value)

    assert [issue.category for issue in issues] == [
        "personal_name",
        "personal_address",
        "personal_name",
        "personal_name",
        "personal_phone",
    ]
    assert [value[issue.start : issue.end] for issue in issues] == [
        "홍길동",
        "[주소]",
        "김철수",
        "[이름]",
        "[연락처]",
    ]


def test_detects_international_phone_number() -> None:
    issues = inspect_skill_instructions("연락처는 +1 415 555 2671입니다.")

    assert [issue.category for issue in issues] == ["personal_phone"]


def test_does_not_treat_dates_versions_or_invalid_numbers_as_personal_data() -> None:
    value = (
        "작성일 2026-08-04, version 1234, "
        "잘못된 주민번호 900101-1234567, 잘못된 카드 4111 1111 1111 1112, "
        "빈 카드 0000 0000 0000 0000"
    )

    assert inspect_skill_instructions(value) == ()
