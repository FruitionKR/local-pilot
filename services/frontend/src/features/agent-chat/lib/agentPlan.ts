export type AgentPlanOperation = {
  id: string;
  sequence: number;
  tool_name: string;
  target_id: string | null;
  source_parent_id: string | null;
  destination_parent_id: string | null;
  arguments: Record<string, unknown>;
  reason: string;
  status: string;
};

export type AgentPlan = {
  id: string;
  version: number;
  operation_hash: string;
  summary: string;
  status: string;
  operations: AgentPlanOperation[];
};

export type AgentPlanRun = {
  id: string;
  status: string;
  error_code: string | null;
  plan: AgentPlan | null;
};

export type PlanTreeItem = {
  id: string;
  name: string;
  type?: "folder" | "document";
  children?: PlanTreeItem[];
};

export type PlanPreviewNode = {
  id: string;
  name: string;
  folder: boolean;
  isNew: boolean;
  children: PlanPreviewNode[];
};

/** 서버 트리를 복사한 뒤 계획 순서대로 적용해 변경 관련 가지를 보여준다. */
export function buildPlanPreviewTree(plan: AgentPlan, tree: PlanTreeItem[]) {
  const nodes = new Map<string, PlanPreviewNode>();
  const parents = new Map<string, string | null>();
  const changed = new Set<string>();
  const created = new Map<string, string>();
  function visit(items: PlanTreeItem[], parent: string | null) {
    for (const item of items) {
      nodes.set(item.id, { id: item.id, name: item.name.normalize("NFC"), folder: item.type === "folder" || item.children !== undefined, isNew: false, children: [] });
      parents.set(item.id, parent);
      visit(item.children ?? [], item.id);
    }
  }
  visit(tree, null);
  function parentId(value: unknown): string | null {
    if (typeof value === "string") return value;
    if (value && typeof value === "object" && "$operation_result" in value) {
      return created.get(String(value.$operation_result)) ?? null;
    }
    return null;
  }
  for (const operation of [...plan.operations].sort((a, b) => a.sequence - b.sequence)) {
    const args = operation.arguments;
    const isCreate = operation.tool_name === "create_folder" || operation.tool_name === "create_document";
    const id = isCreate ? `operation:${operation.id}` : operation.target_id;
    if (!id) continue;
    const folder = operation.tool_name.endsWith("folder");
    if (isCreate) {
      created.set(operation.id, id);
      nodes.set(id, { id, name: String(args.name ?? args.display_name ?? "").normalize("NFC"), folder, isNew: true, children: [] });
    }
    const node = nodes.get(id);
    if (!node) continue;
    changed.add(id);
    if (isCreate || operation.tool_name.startsWith("move_")) {
      parents.set(id, parentId(folder ? args.parent_folder_id : args.folder_id));
    }
    if (operation.tool_name.startsWith("rename_")) node.name = String(args.name ?? args.display_name ?? "").normalize("NFC");
  }
  const roots: PlanPreviewNode[] = [];
  for (const node of nodes.values()) {
    const parent = nodes.get(parents.get(node.id) ?? "");
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  function relevant(items: PlanPreviewNode[]): PlanPreviewNode[] {
    return items.flatMap((node) => {
      const children = relevant(node.children);
      return changed.has(node.id) || children.length ? [{ ...node, children }] : [];
    }).sort((a, b) => Number(b.folder) - Number(a.folder));
  }
  const counts: Record<string, number> = {};
  for (const operation of plan.operations) counts[operation.tool_name] = (counts[operation.tool_name] ?? 0) + 1;
  const summary = [
    ["create_folder", "폴더", "생성"], ["move_document", "문서", "이동"],
    ["move_folder", "폴더", "이동"], ["rename_folder", "폴더", "이름 변경"],
    ["rename_document", "문서", "이름 변경"], ["create_document", "문서", "생성"],
    ["apply_document_edit", "문서", "편집"]
  ].filter(([tool]) => counts[tool]).map(([tool, label, verb]) => `${label} ${counts[tool]}개 ${verb}`).join(" · ");
  return { nodes: relevant(roots), summary };
}

export function isWorkspacePlanAction(action: string | undefined): boolean {
  return action === "folder_organize" || action === "workspace_workflow";
}

export function shouldPollAgentPlan(status: string | undefined): boolean {
  return !status || ["queued", "planning", "awaiting_approval", "executing", "verifying", "cancel_requested", "rolling_back"].includes(status);
}

export function agentPlanStatusLabel(status: string | undefined): string {
  const labels: Record<string, string> = {
    queued: "계획 생성 대기", planning: "계획 생성 중", awaiting_approval: "승인 대기",
    executing: "실행 중", verifying: "결과 확인 중", completed: "실행 완료",
    partial_failed: "일부 작업 실패", failed: "실행 실패", conflicted: "변경 충돌",
    rejected: "계획 거절됨", clarification_required: "추가 설명 필요",
    cancel_requested: "취소 요청 중", rolling_back: "변경 복구 중",
    rollback_failed: "변경 복구 실패", cancelled: "취소됨"
  };
  return status ? labels[status] ?? "상태 확인 필요" : "계획 불러오는 중";
}

export function canApproveAgentPlan(run: AgentPlanRun | null | undefined): boolean {
  return run?.status === "awaiting_approval" && run.plan?.status === "awaiting_approval";
}

/** 새 폴더 참조도 이름으로 풀어 승인할 이동 경로를 보여준다. */
export function describePlanOperations(plan: AgentPlan, tree: PlanTreeItem[]) {
  const paths = new Map<string, string>();
  function visit(items: PlanTreeItem[], parent: string) {
    for (const item of items) {
      const path = parent ? `${parent} / ${item.name}` : item.name;
      paths.set(item.id, path);
      visit(item.children ?? [], path);
    }
  }
  visit(tree, "");
  const createdPaths = new Map<string, string>();
  function destination(value: unknown): string {
    if (value === null) return "최상위";
    if (typeof value === "string") return paths.get(value) ?? "폴더 이름 확인 불가";
    if (value && typeof value === "object" && "$operation_result" in value) {
      return createdPaths.get(String(value.$operation_result)) ?? "새 폴더 이름 확인 불가";
    }
    return "위치 확인 불가";
  }
  return [...plan.operations].sort((a, b) => a.sequence - b.sequence).map((operation) => {
    const args = operation.arguments;
    const target = paths.get(operation.target_id ?? "") ?? "항목 이름 확인 불가";
    const name = String(args.name ?? args.display_name ?? "");
    const parent = destination(operation.tool_name.endsWith("folder") ? args.parent_folder_id : args.folder_id);
    const createdPath = parent === "최상위" ? name : `${parent} / ${name}`;
    let description: string;
    switch (operation.tool_name) {
      case "create_folder":
        createdPaths.set(operation.id, createdPath);
        description = `폴더 생성: ${createdPath}`;
        break;
      case "move_document":
      case "move_folder": description = `${target} → ${parent}`; break;
      case "rename_folder":
      case "rename_document": description = `이름 변경: ${target} → ${name}`; break;
      case "create_document": description = `문서 생성: ${createdPath}`; break;
      case "apply_document_edit": description = `문서 편집: ${target}`; break;
      default: description = "지원하지 않는 작업";
    }
    return { ...operation, description: description.normalize("NFC") };
  });
}
