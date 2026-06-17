import type { ChangeEvent as ReactChangeEvent } from "react";
import { useRef } from "react";
import { uploadDocumentFile } from "../_lib/api";
import {
  appendItemsToFolder,
  applyUploadedDocument,
  createClientId,
  isSupportedUploadFile,
  updateTreeItemStatus
} from "../_lib/tree";
import type { DocumentItemResponse, FileDropTarget, Project, UploadPickerTarget } from "../_lib/types";

export function useDocumentUpload({
  setProjects,
  setDocuments,
  setFileDropTarget,
  refreshBackendData
}: {
  setProjects: React.Dispatch<React.SetStateAction<Project[]>>;
  setDocuments: React.Dispatch<React.SetStateAction<DocumentItemResponse[]>>;
  setFileDropTarget: React.Dispatch<React.SetStateAction<FileDropTarget | null>>;
  refreshBackendData: () => Promise<void>;
}) {
  const uploadPickerTargetRef = useRef<UploadPickerTarget | null>(null);
  const uploadInputRef = useRef<HTMLInputElement | null>(null);

  function openUploadPicker(projectId: string, folderId: string | null) {
    uploadPickerTargetRef.current = { projectId, folderId };
    uploadInputRef.current?.click();
  }

  function handleUploadPickerChange(event: ReactChangeEvent<HTMLInputElement>) {
    const target = uploadPickerTargetRef.current;
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!target || files.length === 0) return;
    dropUploadFiles(target.projectId, target.folderId, files);
  }

  function dropUploadFiles(projectId: string, folderId: string | null, files: File[]) {
    const uploadFiles = files.filter(isSupportedUploadFile);
    setFileDropTarget(null);
    if (uploadFiles.length === 0) return;

    const uploadItems = uploadFiles.map((file) => ({
      id: createClientId("upload"),
      label: file.name,
      type: "file" as const,
      status: "uploading" as const
    }));

    setProjects((current) => current.map((project) => {
      if (project.id !== projectId) return project;
      return { ...project, items: appendItemsToFolder(project.items, folderId, uploadItems) };
    }));

    uploadItems.forEach((item, index) => {
      const file = uploadFiles[index];
      void uploadDocumentFile(file)
        .then((response) => {
          setDocuments((current) => {
            const withoutCurrent = current.filter((document) => document.id !== response.id);
            return [...withoutCurrent, response];
          });
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: applyUploadedDocument(project.items, item.id, response) };
          }));
          void refreshBackendData();
        })
        .catch((error: Error) => {
          setProjects((current) => current.map((project) => {
            if (project.id !== projectId) return project;
            return { ...project, items: updateTreeItemStatus(project.items, item.id, "failed", error.message) };
          }));
        });
    });
  }

  return {
    uploadInputRef,
    openUploadPicker,
    handleUploadPickerChange,
    dropUploadFiles
  };
}
