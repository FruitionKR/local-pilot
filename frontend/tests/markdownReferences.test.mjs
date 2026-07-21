import assert from "node:assert/strict";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { splitMarkdownBlockRanges } from "../app/_lib/markdownSegments.ts";
import { createRehypeSourceBlocks } from "../app/_lib/markdownSourceBlocks.ts";

function renderMarkdown(markdown) {
  const sourceBlocks = splitMarkdownBlockRanges(markdown).map((segment, index) => ({
    ...segment,
    blockId: `B${String(index + 1).padStart(4, "0")}`
  }));

  return renderToStaticMarkup(React.createElement(
    ReactMarkdown,
    {
      remarkPlugins: [remarkGfm],
      rehypePlugins: [createRehypeSourceBlocks(sourceBlocks)]
    },
    markdown
  ));
}

test("footnote reference와 definition을 한 문서 문맥에서 연결한다", () => {
  const markdown = [
    "설명이 필요한 문장입니다.[^1]",
    "",
    "[^1]: 참고 설명입니다."
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.match(html, /data-footnote-ref/);
  assert.match(html, /data-footnotes/);
  assert.match(html, /data-block-id="B0001"/);
  assert.match(html, /data-block-id="B0002"/);
  assert.doesNotMatch(html, />\[\^1\]</);
});

test("reference-style link와 definition을 한 문서 문맥에서 연결한다", () => {
  const markdown = [
    "[API 문서][api]를 확인합니다.",
    "",
    "[api]: https://example.com",
    "",
    "다음 문단"
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.match(html, /<a href="https:\/\/example\.com">API 문서<\/a>/);
  assert.match(html, /data-block-id="B0001"/);
  assert.match(html, /data-block-id="B0003"/);
  assert.doesNotMatch(html, /\[API 문서\]\[api\]/);
});

test("frontmatter 이후 block의 원문 line 범위를 유지한다", () => {
  const markdown = [
    "---",
    "title: API",
    "---",
    "",
    "본문",
    "",
    "## 다음 절"
  ].join("\n");

  assert.deepEqual(splitMarkdownBlockRanges(markdown), [
    { kind: "frontmatter", content: "title: API", startLine: 1, endLine: 3 },
    { kind: "markdown", content: "본문", startLine: 5, endLine: 5 },
    { kind: "markdown", content: "## 다음 절", startLine: 7, endLine: 7 }
  ]);
});

test("H1부터 H6까지 renderer와 source block ID를 유지한다", () => {
  const markdown = [
    "# H1",
    "## H2",
    "### H3",
    "#### H4",
    "##### H5",
    "###### H6"
  ].join("\n");

  const html = renderMarkdown(markdown);

  for (let level = 1; level <= 6; level += 1) {
    assert.match(html, new RegExp(`<h${level}>H${level}</h${level}>`));
    assert.match(html, new RegExp(`data-block-id="B000${level}"`));
  }
});

test("backtick과 tilde fenced code block을 각각 렌더링한다", () => {
  const markdown = [
    "````js",
    "const marker = ```;",
    "````",
    "",
    "~~~python",
    "print(\"hello\")",
    "~~~"
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.match(html, /<code class="language-js">/);
  assert.match(html, /<code class="language-python">/);
  assert.match(html, /data-block-id="B0001"/);
  assert.match(html, /data-block-id="B0002"/);
});
