-- 워크스페이스 아이콘 이미지. 이모지와 배타적이며, 둘 중 하나만 설정된다.
--
-- 바이너리를 object storage가 아니라 DB에 두는 이유: 워크스페이스당 1개·1MB 상한이라
-- 저장량이 원천적으로 묶이고, row가 곧 수명주기라 교체·삭제에 고아 객체가 생기지 않는다.
-- 이미지가 커지거나 CDN이 필요해지면 object storage로 옮긴다(API 계약은 그대로).
--
-- 바이너리를 workspaces가 아니라 별도 테이블에 두는 이유: 목록 조회(GET /api/workspaces)가
-- 워크스페이스마다 최대 1MB를 함께 읽지 않게 한다. workspaces에는 아이콘 유무 판정과
-- 서빙 ETag에 쓰는 작은 값만 남긴다.
ALTER TABLE workspaces
    ADD COLUMN icon_image_content_type character varying(64),
    ADD COLUMN icon_image_hash character varying(64);

CREATE TABLE workspace_icons (
    workspace_id character varying(255) NOT NULL,
    image bytea NOT NULL,
    CONSTRAINT workspace_icons_pkey PRIMARY KEY (workspace_id),
    CONSTRAINT workspace_icons_workspace_fk
        FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE
);

-- 이모지와 이미지는 동시에 설정될 수 없고, 이미지 metadata 두 컬럼은 함께 채워지거나 함께 비어야 한다.
ALTER TABLE workspaces
    ADD CONSTRAINT workspaces_icon_exclusive CHECK (
        (icon_emoji IS NULL OR icon_image_hash IS NULL)
        AND (icon_image_hash IS NULL) = (icon_image_content_type IS NULL)
    );
