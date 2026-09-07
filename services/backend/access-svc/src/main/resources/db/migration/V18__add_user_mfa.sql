-- TOTP 기반 다단계 인증.
--
-- secret은 비밀번호와 달리 검증에 원문이 필요해 해시로 둘 수 없다. 그래서 AES-GCM으로
-- 암호화해 저장한다(키는 MFA_ENCRYPTION_KEY). 평문으로 두면 DB 유출 시 MFA가 통째로 무력화된다.
CREATE TABLE user_mfa (
    user_id character varying(255) NOT NULL,
    secret_cipher bytea NOT NULL,
    secret_nonce bytea NOT NULL,
    -- NULL이면 등록만 하고 아직 코드 검증을 통과하지 못한 상태다. 이 상태는 로그인을 막지 않는다.
    activated_at timestamp with time zone,
    -- 마지막으로 성공한 TOTP 시간 창. 같은 창의 코드를 두 번 쓰지 못하게 한다.
    last_used_counter bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_mfa_pkey PRIMARY KEY (user_id),
    CONSTRAINT user_mfa_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 기기를 잃었을 때 쓰는 1회용 코드. 원문은 발급 시 한 번만 보여주고 해시만 저장한다.
CREATE TABLE user_mfa_recovery_codes (
    id bigserial NOT NULL,
    user_id character varying(255) NOT NULL,
    code_hash character varying(128) NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_mfa_recovery_codes_pkey PRIMARY KEY (id),
    CONSTRAINT user_mfa_recovery_codes_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_mfa_recovery_codes_user ON user_mfa_recovery_codes (user_id) WHERE consumed_at IS NULL;

-- "비밀번호는 통과했지만 아직 코드를 못 받은" 중간 상태. 토큰 원문은 응답으로만 나가고 해시만 저장한다.
CREATE TABLE user_mfa_challenges (
    id character varying(64) NOT NULL,
    user_id character varying(255) NOT NULL,
    token_hash character varying(128) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_mfa_challenges_pkey PRIMARY KEY (id),
    CONSTRAINT user_mfa_challenges_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_user_mfa_challenges_token_hash ON user_mfa_challenges (token_hash);
