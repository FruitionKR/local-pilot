import assert from "node:assert/strict";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { splitMarkdownBlockRanges, splitMarkdownBlocks } from "../src/shared/lib/markdownSegments.ts";
import { createRehypeSourceBlocks } from "../src/shared/lib/markdownSourceBlocks.ts";

function renderMarkdown(markdown, highlightedBlocks = []) {
  const sourceBlocks = splitMarkdownBlockRanges(markdown).map((segment, index) => ({
    ...segment,
    blockId: `B${String(index + 1).padStart(4, "0")}`
  }));
  const highlightedRankByBlockId = new Map(
    highlightedBlocks.map((highlight) => [highlight.block_id, highlight.rank])
  );

  return renderToStaticMarkup(React.createElement(
    ReactMarkdown,
    {
      // MarkdownViewer와 동일하게 원본 HTML을 렌더하지 않는다
      skipHtml: true,
      remarkPlugins: [remarkGfm],
      rehypePlugins: [createRehypeSourceBlocks(sourceBlocks)],
      components: {
        "source-block": ({ node, children }) => {
          const blockId = String(node?.properties?.dataBlockId ?? "");
          const highlightedRank = highlightedRankByBlockId.get(blockId);
          return React.createElement("div", {
            className: highlightedRank
              ? `markdown-source-block is-highlighted citation-rank-${highlightedRank}`
              : "markdown-source-block",
            "data-block-id": blockId,
            "data-citation-rank": highlightedRank
          }, children);
        }
      }
    },
    markdown
  ));
}

test("노트 marker를 포함한 원문에서 citation block을 실제 필드에 강조한다", () => {
  const markdown = [
    "<!-- fruition-note: note-1 -->",
    "# 제목",
    "",
    "첫 번째 필드",
    "",
    "강조할 필드"
  ].join("\n");

  const html = renderMarkdown(markdown, [{ block_id: "B0004", rank: 2 }]);

  assert.match(
    html,
    /class="markdown-source-block is-highlighted citation-rank-2" data-block-id="B0004" data-citation-rank="2"><p>강조할 필드<\/p>/
  );
  assert.doesNotMatch(
    html,
    /class="markdown-source-block is-highlighted citation-rank-2" data-block-id="B0003"/
  );
});

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

test("GFM table 원문과 table 구조를 보존한다", () => {
  const markdown = [
    "| 이름 | 상태 |",
    "| --- | --- |",
    "| API \\| SDK | 완료 |"
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
  assert.match(html, /<table>/);
  assert.match(html, /<th>이름<\/th>/);
  assert.match(html, /<td>API \| SDK<\/td>/);
  assert.match(html, /data-block-id="B0001"/);
});

test("GFM task list 원문과 checkbox 상태를 보존한다", () => {
  const markdown = [
    "- [ ] 미완료",
    "- [x] 완료"
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
  assert.match(html, /class="contains-task-list"/);
  assert.equal((html.match(/type="checkbox"/g) ?? []).length, 2);
  assert.equal((html.match(/checked=""/g) ?? []).length, 1);
  assert.match(html, /data-block-id="B0001"/);
});

test("Markdown link 원문과 URL을 보존한다", () => {
  const markdown = "[공식 문서](https://example.com/docs \"문서\")";
  const html = renderMarkdown(markdown);

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
  assert.match(html, /<a href="https:\/\/example\.com\/docs" title="문서">공식 문서<\/a>/);
  assert.match(html, /data-block-id="B0001"/);
});

test("Markdown image 원문과 src·alt·title을 보존한다", () => {
  const markdown = "![구조도](https://example.com/diagram.png \"시스템 구조\")";
  const html = renderMarkdown(markdown);

  assert.deepEqual(splitMarkdownBlocks(markdown), [
    { kind: "markdown", content: markdown }
  ]);
  assert.match(
    html,
    /<img src="https:\/\/example\.com\/diagram\.png" alt="구조도" title="시스템 구조"\/?>/
  );
  assert.match(html, /data-block-id="B0001"/);
});

test("GFM autolink literal과 strikethrough를 렌더링한다", () => {
  const markdown = [
    "https://example.com/docs",
    "",
    "~~삭제된 내용~~"
  ].join("\n");
  const html = renderMarkdown(markdown);

  assert.match(
    html,
    /<a href="https:\/\/example\.com\/docs">https:\/\/example\.com\/docs<\/a>/
  );
  assert.match(html, /<del>삭제된 내용<\/del>/);
  assert.match(html, /data-block-id="B0001"/);
  assert.match(html, /data-block-id="B0002"/);
});

test("에디터가 빈 줄 보존용으로 남긴 <br />를 글자로 렌더링하지 않는다", () => {
  const markdown = [
    "첫 문단",
    "",
    "<br />",
    "",
    "둘째 문단"
  ].join("\n");

  const html = renderMarkdown(markdown);

  assert.doesNotMatch(html, /&lt;br \/&gt;/);
  assert.match(html, /첫 문단/);
  assert.match(html, /둘째 문단/);
});
