import MarkdownIt from 'markdown-it';
import { describe, expect, it } from 'vitest';
import { configureMarkdownCitations, type MarkdownCitationEnvironment } from '../src/utils/markdownCitations';
import { configureMarkdownLinkPolicy } from '../src/utils/markdownLinkPolicy';
import { normalizeLooseMarkdown } from '../src/utils/markdownNormalization.mjs';

const markdown = configureMarkdownCitations(configureMarkdownLinkPolicy(new MarkdownIt({
  html: false, linkify: true, breaks: true,
})));

function render(content: string, workspaceCitations = true) {
  const env: MarkdownCitationEnvironment = {
    workspaceCitations, citations: [], citationLabel: (index) => `查看引用来源 ${index}`,
  };
  return { html: markdown.render(content, env), citations: env.citations };
}

describe('workspace Markdown citations', () => {
  it('compacts the screenshot markers and reuses numbers without losing source locators', () => {
    const result = render('导入副本 (citation: PaperAgent-__-.md#chunk-7)。\n\n'
      + '例如 (citation: PaperAgent-__-.md#chunk-7)。\n\n'
      + '自动保存（citation: 使用说明.md#chunk-8、 #chunk-9）。');

    expect(result.citations).toEqual(['PaperAgent-__-.md#chunk-7', '使用说明.md#chunk-8、 #chunk-9']);
    expect(result.html.match(/data-citation-index="1"/g)).toHaveLength(2);
    expect(result.html).toContain('aria-label="查看引用来源 2"');
    expect(result.html).toContain('>[2]</button>');
    expect(result.html).not.toContain('(citation:');
    expect(result.html).not.toContain('href=');
  });

  it('does not change the default or Project renderer output', () => {
    const source = '## 标题\n\n**加粗** (citation: guide.md#chunk-7)\n\n'
      + '[说明](https://example.com)\n\n```java\n    run();\n```';
    const original = configureMarkdownLinkPolicy(new MarkdownIt({ html: false, linkify: true, breaks: true }));

    expect(render(source, false)).toEqual({ html: original.render(source), citations: [] });
  });

  it('leaves code, links, unrecognized citations and unfinished streamed markers intact', () => {
    const result = render('`(citation: inline.md#chunk-1)`\n\n'
      + '```text\n(citation: fenced.md#chunk-2)\n```\n\n'
      + '    (citation: indented.md#chunk-3)\n\n'
      + '[(citation: linked.md#chunk-4)](https://example.com)\n\n'
      + '(citation: author 2026)\n\n(citation: incomplete.md#chunk-5');

    expect(result.citations).toEqual([]);
    expect(result.html).not.toContain('<button');
    expect(result.html).toContain('<code>(citation: inline.md#chunk-1)</code>');
    expect(result.html).toContain('<a href="https://example.com">(citation: linked.md#chunk-4)</a>');
    expect(result.html).toContain('(citation: incomplete.md#chunk-5');
  });

  it('escapes source attributes and never interprets a marker as executable HTML', () => {
    const source = 'guide" onfocus="alert#chunk-1';
    const result = render(`(citation: ${source}) <script>alert(1)</script>`);

    expect(result.citations).toEqual([source]);
    expect(result.html).toContain('title="guide&quot; onfocus=&quot;alert#chunk-1"');
    expect(result.html).not.toContain(' onfocus="');
    expect(result.html).not.toContain('<script>');
  });

  it('does not carry citations from another response or an earlier streamed render', () => {
    expect(render('(citation: guide.md#chunk-1)').citations).toHaveLength(1);
    expect(render('普通回答').citations).toEqual([]);
    expect(render('(citation: another.md#chunk-2)').html).toContain('>[1]</button>');
  });

  it('keeps headings, ordered steps, emphasis and code indentation with whitespace deltas', () => {
    const deltas = ['## 一、基本操作流程', '\n\n', '1.', ' ', '**进入项目页面**', '\n',
      '2.', ' ', '**描述任务**', '\n\n', '```java', '\n', '    ', 'run();', '\n', '\t', 'finish();', '\n', '```'];
    const result = render(normalizeLooseMarkdown(deltas.join(''), { demoteSpacedProseHeadings: true }));

    expect(result.html).toContain('<h2>一、基本操作流程</h2>');
    expect(result.html).toContain('<ol>');
    expect(result.html).toContain('<li><strong>进入项目页面</strong></li>');
    expect(result.html).toContain('<li><strong>描述任务</strong></li>');
    expect(result.html).toContain('    run();\n\tfinish();');
    expect(result.html).not.toContain('基本操作流程1.');
  });
});
