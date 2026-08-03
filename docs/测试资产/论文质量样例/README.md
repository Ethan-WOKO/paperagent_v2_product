# Paper Quality Samples

本目录记录 E-09 论文质量样例集的使用方式。真实 fixture 位于：

- `yanban-paper/src/test/resources/paper-quality-samples/zh-rag-polish/`
- `yanban-paper/src/test/resources/paper-quality-samples/en-literature-gap/`
- `yanban-paper/src/test/resources/paper-quality-samples/inline-bibliography/`

## 样例目标

1. 中文 LaTeX + `.bib`：验证中文章节、cite/ref/figure/math 保护与文献可追溯核对。
2. 英文 LaTeX + `.bib`：验证英文论文常规结构、equation/ref/math 保护与 gap/review 记录。
3. 无 `.bib` / 内联 `thebibliography`：验证 legacy 模板中内联参考文献解析。

## 推荐验收顺序

1. 运行 `mvn -pl yanban-paper test`，确认样例均可被静态解析。
2. 在论文页或后端任务流上传中文样例并执行到 ASSEMBLE。
3. 下载并保存三件套：`polished.tex`、`suggested.bib`、`review_report.md`。
4. 对英文样例重复第 2~3 步。
5. 填写人工评价，重点核对：推荐文献是否来自真实检索结果、占位符是否残留、cite key 是否存在、受保护元素是否被破坏。
