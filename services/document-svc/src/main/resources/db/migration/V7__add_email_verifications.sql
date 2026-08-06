-- 이메일 인증 기반 회원가입·비밀번호 재설정을 위한 인증번호/토큰 저장 테이블.
-- 인증번호(code)와 verification_token은 원문 대신 SHA-256 해시만 저장한다.
-- 흐름: 발급(request) → 검증(confirm, verification_token 발급) → 최종 소비(signup/password-reset).

CREATE TABLE email_verifications (
    id                character varying(64) PRIMARY KEY,
    email             character varying(255) NOT NULL,
    purpose           character varying(32) NOT NULL,
    code_hash         character varying(128) NOT NULL,
    code_expires_at   timestamp with time zone NOT NULL,
    attempt_count     integer NOT NULL DEFAULT 0,
    confirmed_at      timestamp with time zone,
    token_hash        character varying(128),
    token_expires_at  timestamp with time zone,
    consumed_at       timestamp with time zone,
    created_at        timestamp with time zone NOT NULL DEFAULT now()
);

-- cooldown/일일 상한 계산: (email, purpose)별 최근·당일 발급 조회.
CREATE INDEX idx_email_verifications_email_purpose_created
    ON email_verifications (email, purpose, created_at);

-- 최종 소비 단계에서 verification_token 해시로 역조회.
CREATE INDEX idx_email_verifications_token_hash
    ON email_verifications (token_hash);
