import { Fragment, type ReactNode } from "react";

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`|\[\[[^\]|]+(?:\|[^\]]+)?\]\])/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) nodes.push(text.slice(lastIndex, match.index));
    const token = match[0];
    if (token.startsWith("**")) {
      nodes.push(<strong key={`${match.index}-strong`}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith("`")) {
      nodes.push(<code key={`${match.index}-code`}>{token.slice(1, -1)}</code>);
    } else {
      const body = token.slice(2, -2);
      const label = body.includes("|") ? body.split("|")[1] : body;
      nodes.push(<span className="markdown-wikilink" key={`${match.index}-wikilink`}>{label}</span>);
    }
    lastIndex = match.index + token.length;
  }

  if (lastIndex < text.length) nodes.push(text.slice(lastIndex));
  return nodes;
}

export function MarkdownViewer({ markdown }: { markdown: string }) {
  const blocks: ReactNode[] = [];
  const lines = markdown.split("\n");
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    const trimmed = line.trim();

    if (!trimmed) {
      index += 1;
      continue;
    }

    if (trimmed === "---" && blocks.length === 0) {
      const frontmatter = [];
      index += 1;
      while (index < lines.length && lines[index].trim() !== "---") {
        frontmatter.push(lines[index]);
        index += 1;
      }
      index += 1;
      blocks.push(
        <details className="markdown-frontmatter" key={`frontmatter-${index}`}>
          <summary>Metadata</summary>
          <pre>{frontmatter.join("\n")}</pre>
        </details>
      );
      continue;
    }

    if (trimmed.startsWith("```")) {
      const code = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      while (code.length > 0 && code[code.length - 1].trim() === "") code.pop();
      index += 1;
      blocks.push(<pre className="markdown-codeblock" key={`code-${index}`}><code>{code.join("\n")}</code></pre>);
      continue;
    }

    if (trimmed.startsWith("# ")) {
      blocks.push(<h1 key={`h1-${index}`}>{renderInline(trimmed.slice(2))}</h1>);
      index += 1;
      continue;
    }

    if (trimmed.startsWith("## ")) {
      blocks.push(<h2 key={`h2-${index}`}>{renderInline(trimmed.slice(3))}</h2>);
      index += 1;
      continue;
    }

    if (trimmed.startsWith("### ")) {
      blocks.push(<h3 key={`h3-${index}`}>{renderInline(trimmed.slice(4))}</h3>);
      index += 1;
      continue;
    }

    if (trimmed === "---" || trimmed === "***") {
      blocks.push(<hr key={`hr-${index}`} />);
      index += 1;
      continue;
    }

    if (/^- /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^- /.test(lines[index].trim())) {
        items.push(lines[index].trim().slice(2));
        index += 1;
      }
      blocks.push(
        <ul key={`ul-${index}`}>
          {items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}
        </ul>
      );
      continue;
    }

    if (/^\d+\. /.test(trimmed)) {
      const items = [];
      while (index < lines.length && /^\d+\. /.test(lines[index].trim())) {
        items.push(lines[index].trim().replace(/^\d+\. /, ""));
        index += 1;
      }
      blocks.push(
        <ol key={`ol-${index}`}>
          {items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}
        </ol>
      );
      continue;
    }

    const paragraph = [trimmed];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^(#{1,3} |[-*]{3}$|- |\d+\. |```)/.test(lines[index].trim())) {
      paragraph.push(lines[index].trim());
      index += 1;
    }
    blocks.push(<p key={`p-${index}`}>{renderInline(paragraph.join(" "))}</p>);
  }

  return <div className="markdown-viewer">{blocks.map((block, blockIndex) => <Fragment key={blockIndex}>{block}</Fragment>)}</div>;
}
