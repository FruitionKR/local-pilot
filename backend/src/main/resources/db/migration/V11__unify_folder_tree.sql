-- 파일탐색기식 단일 폴더 트리로 통일한다.
-- source_folders를 folders로 일반화하고, documents의 역할별 배치(parent_document_id / source_folder_id)를
-- 단일 folder_id로 합친다. 문서는 leaf이므로 parent_document_id 계층을 제거한다.

-- 1) source_folders 테이블과 제약·인덱스를 folders로 일반화
ALTER TABLE source_folders RENAME TO folders;
ALTER INDEX idx_source_folders_parent_order RENAME TO idx_folders_parent_order;
ALTER TABLE folders RENAME CONSTRAINT fk_source_folders_workspace TO fk_folders_workspace;
ALTER TABLE folders RENAME CONSTRAINT fk_source_folders_parent TO fk_folders_parent;
ALTER TABLE folders RENAME CONSTRAINT fk_source_folders_deleted_by TO fk_folders_deleted_by;

-- 2) documents: 역할별 배치 제약과 부모 문서 계층 제거
ALTER TABLE documents DROP CONSTRAINT documents_role_parent_check;
DROP INDEX idx_documents_editable_parent_order;
DROP INDEX idx_documents_original_folder_order;
ALTER TABLE documents DROP CONSTRAINT fk_documents_parent_document;
ALTER TABLE documents DROP COLUMN parent_document_id;

-- 3) source_folder_id를 folder_id로 일반화하고 단일 폴더 배치 인덱스를 만든다
ALTER TABLE documents RENAME COLUMN source_folder_id TO folder_id;
ALTER TABLE documents RENAME CONSTRAINT fk_documents_source_folder TO fk_documents_folder;
CREATE INDEX idx_documents_folder_order
    ON documents(workspace_id, folder_id, sort_order);
