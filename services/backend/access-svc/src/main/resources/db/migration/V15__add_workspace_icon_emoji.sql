-- 워크스페이스 아이콘(이모지). 이미지 업로드는 아직 지원하지 않으며, 도입 시 별도 컬럼을 더한다.
-- 32자: 가족 이모지처럼 ZWJ로 이어 붙는 시퀀스가 코드포인트 여러 개를 쓴다.
ALTER TABLE workspaces ADD COLUMN icon_emoji character varying(32);
