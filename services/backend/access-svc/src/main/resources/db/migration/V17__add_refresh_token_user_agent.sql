-- 세션 목록에서 기기를 구분하기 위한 User-Agent 원문. 없으면 목록이 시각만 나열돼 쓸모가 없다.
-- 과거 발급분은 NULL이며 화면에서 "알 수 없는 기기"로 보인다.
ALTER TABLE user_refresh_tokens ADD COLUMN user_agent character varying(512);
