-- 워크스페이스 이메일 초대. 수락 전까지 멤버가 되지 않으며, 수락 시점에
-- 로그인한 계정이 멤버로 편입된다(계정은 (email, provider)로 분리돼 있어 초대만으로는 특정 불가).
CREATE TABLE workspace_invitations (
    id character varying(64) NOT NULL,
    workspace_id character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    token_hash character varying(128) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    invited_by character varying(255) NOT NULL,
    accepted_at timestamp with time zone,
    accepted_by character varying(255),
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT workspace_invitations_pkey PRIMARY KEY (id),
    CONSTRAINT workspace_invitations_role_check
        CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'MEMBER'::character varying])::text[]))),
    CONSTRAINT workspace_invitations_workspace_fk
        FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT workspace_invitations_invited_by_fk
        FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT workspace_invitations_accepted_by_fk
        FOREIGN KEY (accepted_by) REFERENCES users(id) ON DELETE SET NULL
);

-- 토큰으로 초대를 찾는 경로.
CREATE UNIQUE INDEX idx_workspace_invitations_token_hash
    ON workspace_invitations (token_hash);

-- 대기 중 초대는 (워크스페이스, 이메일)당 1건. 재초대는 새 행이 아니라 기존 행의 재발송이다.
CREATE UNIQUE INDEX idx_workspace_invitations_pending
    ON workspace_invitations (workspace_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
