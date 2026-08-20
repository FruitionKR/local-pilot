-- users 행이 지워지면 user_oauth_accounts에 고아 링크가 남는다. 그 상태에서 해당
-- provider로 로그인하면 링크는 찾았는데 계정이 없어 500으로 끝난다.
-- V1 baseline이 workspace_members·idempotency_records에만 FK를 걸어둔 누락을 메운다.

DELETE FROM public.user_oauth_accounts o
WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = o.user_id);

ALTER TABLE public.user_oauth_accounts
    ADD CONSTRAINT fk_user_oauth_accounts_user
    FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
