import { useCallback, useEffect, useState } from "react";
import { fetchBackendData } from "../_lib/api";
import { mergeBackendDataIntoProjects } from "../_lib/tree";
import type { DocumentItemResponse, Project, WikiGraphResponse } from "../_lib/types";

export function useBackendData({
  setProjects
}: {
  setProjects: React.Dispatch<React.SetStateAction<Project[]>>;
}) {
  const [documents, setDocuments] = useState<DocumentItemResponse[]>([]);
  const [wikiGraph, setWikiGraph] = useState<WikiGraphResponse>({ nodes: [], edges: [] });
  const [isGraphLoading, setIsGraphLoading] = useState(true);
  const [apiError, setApiError] = useState<string | null>(null);
  const hasProcessingDocuments = documents.some((document) => document.status === "processing" || document.status === "uploaded");

  const refreshBackendData = useCallback(async () => {
    try {
      const nextData = await fetchBackendData();
      setDocuments(nextData.documents);
      setWikiGraph(nextData.graph);
      setProjects((current) => mergeBackendDataIntoProjects(current, nextData.documents, nextData.graph));
      setApiError(null);
    } catch (error) {
      setApiError(error instanceof Error ? error.message : "백엔드 데이터를 불러오지 못했습니다.");
    } finally {
      setIsGraphLoading(false);
    }
  }, [setProjects]);

  useEffect(() => {
    void refreshBackendData();
  }, [refreshBackendData]);

  useEffect(() => {
    if (!hasProcessingDocuments) return;
    const intervalId = window.setInterval(() => {
      void refreshBackendData();
    }, 3000);
    return () => window.clearInterval(intervalId);
  }, [hasProcessingDocuments, refreshBackendData]);

  return {
    documents,
    setDocuments,
    wikiGraph,
    isGraphLoading,
    apiError,
    refreshBackendData
  };
}
