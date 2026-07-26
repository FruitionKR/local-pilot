-- parent_document_id는 서비스에서 쓰이지 않는 문서 자기참조 컬럼이라 제거한다.
-- 이후 문서 계층은 폴더(source_folder_id)만 사용하며, 편집 문서도 폴더에 배치할 수 있다.

-- EDITABLE→source_folder_id NULL, ORIGINAL→parent_document_id NULL 을 강제하던 제약 제거.
ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_role_parent_check;

-- parent_document_id 전용 인덱스 제거.
DROP INDEX IF EXISTS idx_documents_editable_parent_order;

-- parent_document_id 자기참조 FK 제거 후 컬럼 제거.
ALTER TABLE documents DROP CONSTRAINT IF EXISTS fk_documents_parent_document;
ALTER TABLE documents DROP COLUMN IF EXISTS parent_document_id;
