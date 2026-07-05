import { useCallback, useEffect } from "react";
import type { Dispatch, SetStateAction } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchBackendData } from "../_lib/api";
import { getErrorMessage } from "../_lib/errors";
import { mergeBackendDataIntoProjects } from "../_lib/tree";
import type { BackendData, DocumentItemResponse, Project, WikiGraphResponse } from "../_lib/types";

const PROCESSING_POLL_INTERVAL_MS = 3000;
const BACKEND_DATA_QUERY_KEY = ["backendData"] as const;
const EMPTY_GRAPH: WikiGraphResponse = { nodes: [], edges: [] };

function hasProcessingDocuments(data: BackendData | undefined) {
  return (data?.documents ?? []).some(
    (document) => document.status === "processing" || document.status === "uploaded"
  );
}

export function useBackendData({
  setProjects
}: {
  setProjects: Dispatch<SetStateAction<Project[]>>;
}) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: BACKEND_DATA_QUERY_KEY,
    queryFn: fetchBackendData,
    // 처리 중(processing/uploaded) 문서가 있을 때만 3초 폴링한다.
    refetchInterval: (activeQuery) =>
      hasProcessingDocuments(activeQuery.state.data) ? PROCESSING_POLL_INTERVAL_MS : false,
    // 폴링으로 갱신 주기를 이미 제어하므로 탭 포커스마다 refetch하지 않는다.
    refetchOnWindowFocus: false
  });

  const backendData = query.data;

  // 백엔드 데이터가 갱신될 때 프로젝트 트리에 병합한다.
  useEffect(() => {
    if (!backendData) return;
    setProjects((current) => mergeBackendDataIntoProjects(current, backendData.documents, backendData.graph));
  }, [backendData, setProjects]);

  const { refetch } = query;
  const refreshBackendData = useCallback(async () => {
    await refetch();
  }, [refetch]);

  /** 업로드 낙관적 갱신용: query cache의 documents를 직접 수정한다. */
  const setDocuments = useCallback(
    (action: SetStateAction<DocumentItemResponse[]>) => {
      queryClient.setQueryData<BackendData>(BACKEND_DATA_QUERY_KEY, (current) => {
        const base = current ?? { documents: [], graph: EMPTY_GRAPH };
        const nextDocuments = typeof action === "function" ? action(base.documents) : action;
        return { ...base, documents: nextDocuments };
      });
    },
    [queryClient]
  );

  return {
    documents: backendData?.documents ?? [],
    setDocuments,
    wikiGraph: backendData?.graph ?? EMPTY_GRAPH,
    isGraphLoading: query.isLoading,
    apiError: query.error ? getErrorMessage(query.error, "백엔드 데이터를 불러오지 못했습니다.") : null,
    refreshBackendData
  };
}
