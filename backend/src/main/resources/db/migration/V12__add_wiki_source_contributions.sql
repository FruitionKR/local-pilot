-- 문서별 최신 편입 결과를 보존해 lint가 재편입 전후 차이를 안전하게 정리할 수 있게 한다.
CREATE TABLE public.wiki_source_contributions (
    pipeline_run_id uuid PRIMARY KEY REFERENCES public.pipeline_runs(id) ON DELETE CASCADE,
    document_id character varying(255) NOT NULL REFERENCES public.documents(id) ON DELETE CASCADE,
    user_id character varying(255) NOT NULL,
    workspace_id character varying(255) NOT NULL,
    payload jsonb NOT NULL,
    active boolean NOT NULL DEFAULT true,
    structural_reconciled_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_wiki_source_contributions_active_document
    ON public.wiki_source_contributions (document_id)
    WHERE active;

CREATE INDEX idx_wiki_source_contributions_workspace
    ON public.wiki_source_contributions (user_id, workspace_id, active);
