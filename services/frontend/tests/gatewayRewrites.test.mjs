import assert from "node:assert/strict";
import test from "node:test";

import nextConfig from "../next.config.mjs";

test("workspace 복구 요청은 access-svc로 전달한다", async () => {
  const rewrites = await nextConfig.rewrites();
  const restore = rewrites.find((route) => route.source === "/api/workspaces/:wid/restore");
  const accessUrl = process.env.NEXT_PUBLIC_ACCESS_URL || "http://localhost:8081";

  assert.equal(restore?.destination, `${accessUrl}/api/workspaces/:wid/restore`);
});
