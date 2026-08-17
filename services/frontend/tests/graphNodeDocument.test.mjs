import assert from "node:assert/strict";
import test from "node:test";
import { resolveNodeDocumentId } from "../src/entities/graph/lib/graph.ts";

const nodes = [
  { id: "raw:doc-1", label: "원본", kind: "raw", documentId: "doc-1" },
  { id: "page-1", label: "source page", kind: "source", documentId: "doc-2" },
  { id: "page-2", label: "concept page", kind: "concept" }
];

test("raw 노드 ID에서 documentId를 복원한다", () => {
  assert.equal(resolveNodeDocumentId(nodes, "raw:doc-1"), "doc-1");
});

test("source 노드는 연결된 원본 문서 ID를 돌려준다", () => {
  assert.equal(resolveNodeDocumentId(nodes, "page-1"), "doc-2");
});

test("concept 노드는 연결된 문서가 없으므로 null이다", () => {
  assert.equal(resolveNodeDocumentId(nodes, "page-2"), null);
});

test("선택된 노드가 없으면 null이다", () => {
  assert.equal(resolveNodeDocumentId(nodes, null), null);
});

test("목록에 없는 노드 ID는 null이다", () => {
  assert.equal(resolveNodeDocumentId(nodes, "unknown"), null);
});

test("raw 접두사만 있고 documentId가 비면 null이다", () => {
  assert.equal(resolveNodeDocumentId(nodes, "raw:"), null);
});
