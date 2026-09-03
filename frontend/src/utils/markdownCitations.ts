import type MarkdownIt from 'markdown-it';
import type Token from 'markdown-it/lib/token.mjs';

export interface MarkdownCitationEnvironment {
  workspaceCitations?: boolean;
  citations: string[];
  citationLabel: (index: number) => string;
}

/** Present explicit knowledge chunk markers without inventing a document URL. */
export function configureMarkdownCitations(markdown: MarkdownIt) {
  markdown.core.ruler.after('text_join', 'workspace_citations', (state) => {
    const env = state.env as MarkdownCitationEnvironment;
    if (!env.workspaceCitations) return;
    env.citations = [];
    const indices = new Map<string, number>();

    for (const block of state.tokens) {
      if (block.type !== 'inline' || !block.children) continue;
      const children: Token[] = [];
      let linkDepth = 0;
      for (const token of block.children) {
        if (token.type === 'link_open') linkDepth += 1;
        if (token.type === 'link_close') linkDepth -= 1;
        // Never rewrite links, code spans, fenced code, or formatted/non-text tokens.
        if (token.type !== 'text' || linkDepth > 0) {
          children.push(token);
          continue;
        }
        const pattern = /\(citation[ \t]*:[ \t]*([^()\r\n]+)\)|（citation[ \t]*:[ \t]*([^（）\r\n]+)）/gi;
        let offset = 0;
        for (const match of token.content.matchAll(pattern)) {
          const source = (match[1] ?? match[2]).trim();
          // Incomplete streamed markers and unknown citation formats stay visible as text.
          if (source.length > 1024 || !/\S.*#chunk-[\w-]+/.test(source)) continue;
          const before = new state.Token('text', '', 0);
          before.content = token.content.slice(offset, match.index);
          children.push(before);
          let index = indices.get(source);
          if (index === undefined) {
            env.citations.push(source);
            index = env.citations.length;
            indices.set(source, index);
          }
          const citation = new state.Token('workspace_citation', '', 0);
          citation.content = source;
          citation.meta = { index };
          children.push(citation);
          offset = match.index! + match[0].length;
        }
        const after = new state.Token('text', '', 0);
        after.content = token.content.slice(offset);
        children.push(after);
      }
      block.children = children;
    }
  });

  markdown.renderer.rules.workspace_citation = (tokens, index, _options, environment) => {
    const token = tokens[index];
    const number = token.meta.index as number;
    const env = environment as MarkdownCitationEnvironment;
    const escape = markdown.utils.escapeHtml;
    return `<button type="button" class="markdown-citation" data-citation-index="${number}"`
      + ` aria-label="${escape(env.citationLabel(number))}" title="${escape(token.content)}">[${number}]</button>`;
  };
  return markdown;
}
