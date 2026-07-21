const NOTE_MARKER_PATTERN = /^(<!--\s*fruition-(?:note|workspace):\s*[^\r\n]+?\s*-->)\r?\n?/;

export type EditableNoteMarkdown = {
  marker: string;
  body: string;
};

export function splitEditableNoteMarkdown(markdown: string): EditableNoteMarkdown | null {
  const match = markdown.match(NOTE_MARKER_PATTERN);
  if (!match) return null;
  return {
    marker: match[1],
    body: markdown.slice(match[0].length)
  };
}

export function composeEditableNoteMarkdown(marker: string, body: string) {
  return `${marker}\n${body}${body.endsWith("\n") ? "" : "\n"}`;
}
