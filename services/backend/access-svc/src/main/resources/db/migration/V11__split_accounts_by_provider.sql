-- 같은 이메일이라도 계정을 만든 수단(provider)이 다르면 서로 다른 계정으로 취급한다.
-- users.email 단독 UK를 (email, provider) UK로 교체한다.

ALTER TABLE public.users ADD COLUMN provider character varying(255);

-- 백필: 비밀번호가 있으면 일반 회원가입 계정('local')이다. 비밀번호 로그인 경로를
-- 잃지 않도록 OAuth 링크가 함께 있어도 'local'을 우선한다.
-- 비밀번호가 없으면 OAuth로 만들어진 계정이므로 가장 먼저 연결된 provider를 쓴다.
UPDATE public.users u
SET provider = CASE
        WHEN u.password_hash IS NOT NULL THEN 'local'
        ELSE COALESCE(
            (SELECT o.provider
               FROM public.user_oauth_accounts o
              WHERE o.user_id = u.id
              ORDER BY o.created_at, o.id
              LIMIT 1),
            'local')
    END;

ALTER TABLE public.users ALTER COLUMN provider SET NOT NULL;

ALTER TABLE public.users DROP CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7;
ALTER TABLE public.users ADD CONSTRAINT uq_users_email_provider UNIQUE (email, provider);

-- 계정 소유 provider와 다른 기존 링크를 해제한다. 이전에는 이메일이 같으면 한 계정에
-- 여러 provider가 붙었는데, 그 링크가 남아 있으면 다음 로그인이 계속 같은 계정으로
-- 들어가 분리가 적용되지 않는다. 링크 행만 지우므로 계정·워크스페이스·문서는 보존된다.
DELETE FROM public.user_oauth_accounts o
USING public.users u
WHERE o.user_id = u.id
  AND o.provider <> u.provider;
