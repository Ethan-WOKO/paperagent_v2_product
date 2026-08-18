export const TASK_CASES = [
  {
    id: 'project-manifest',
    instruction: '列出当前项目的文件总数，并准确指出 Sort.java、LRUCache.java 和 test.py 的相对路径。不要猜测未读取的内容。',
    expect: { state: 'succeeded', tools: ['project.list'], includes: ['src/main/java/Sort.java', 'src/main/java/LRUCache.java', 'src/test.py'] },
  },
  {
    id: 'exact-file-read',
    instruction: '读取 src/main/java/LRUCache.java，概括它的数据结构，并告诉我你实际读取的精确路径。',
    expect: { state: 'succeeded', tools: ['project.read'], includes: ['src/main/java/LRUCache.java', 'HashMap'] },
  },
  {
    id: 'source-summary',
    instruction: '读取 src/main/java/Sort.java，简洁概括其中实现的排序算法，并明确实际读取的文件路径。',
    expect: { state: 'succeeded', tools: ['project.read'], includes: ['src/main/java/Sort.java', '冒泡', '快速'] },
  },
  {
    id: 'symbol-search',
    instruction: '在项目中查找定义 main 方法的源码文件，列出实际匹配到的相对路径；不要逐个读取全部文件。',
    expect: { state: 'succeeded', anyTools: ['project.search', 'project.read', 'project_code_symbols'], includes: ['src/main/java'] },
  },
  {
    id: 'missing-file-honesty',
    instruction: '读取 src/main/java/DefinitelyMissing193.java，并说明结果。文件不存在时必须明确说不存在，不能编造内容。',
    expect: { state: 'succeeded', anyTools: ['project.list', 'project.read', 'project.search'], includes: ['DefinitelyMissing193.java'], excludes: ['已经读取了该文件的内容'] },
  },
  {
    id: 'compile-success',
    instruction: '在沙箱中编译并运行 src/main/java/xhs_1111.java，只依据正式沙箱回执告诉我是否成功。',
    expect: { state: 'succeeded', tools: ['sandbox.execute'], sandbox: true, sandboxSuccess: true },
  },
  {
    id: 'compile-failure-diagnosis',
    instruction: '在沙箱中编译 src/main/java/Sort.java，若失败，准确说明首个编译错误和依据，不要修改文件。',
    expect: { state: 'succeeded', tools: ['sandbox.execute'], sandbox: true, includesAny: ['logback', 'TimeBasedFileNamingAndTriggeringPolicy', 'package ch.qos.logback'] },
  },
  {
    id: 'python-failure-diagnosis',
    instruction: '在沙箱中运行 src/test.py，准确说明成功或失败，并引用正式回执中的关键错误。不要修改文件。',
    expect: { state: 'succeeded', tools: ['sandbox.execute'], sandbox: true, includesAny: ['item_count', 'NameError'] },
  },
  {
    id: 'multi-directory-location',
    instruction: '检查 src 目录，分别指出 Java 主源码、资源、Java 测试和 Python 文件位于哪些目录，只报告实际观察到的路径。',
    expect: { state: 'succeeded', tools: ['project.list'], includes: ['src/main/java', 'src/main/resources', 'src/test/java', 'src/test.py'] },
  },
  {
    id: 'entry-point-selection',
    instruction: '这个项目中哪个文件是 Spring Boot 应用入口？读取必要文件后给出精确相对路径和代码依据。',
    expect: { state: 'succeeded', tools: ['project.read'], includes: ['JavaTestApplication.java', 'SpringBootApplication'] },
  },
  {
    id: 'web-search-sources',
    instruction: '联网搜索 Java 17 单文件源码的编译运行方式，简洁回答，并给出实际搜索结果中的来源链接。不要把网址或命令当作项目文件。',
    expect: { state: 'succeeded', tools: ['search_web'], includesAny: ['https://', 'http://'], evidence: true },
  },
  {
    id: 'knowledge-retrieval',
    instruction: '检索我的知识库中是否有与 Java 排序算法相关的材料。只能依据检索结果回答；没有结果就明确说没有。',
    expect: { state: 'succeeded', tools: ['search_knowledge'] },
  },
  {
    id: 'long-term-memory-priority',
    instruction: '只回答：本次评测约定的代号是什么？若当前指令与长期记忆冲突，以当前指令为准。',
    expect: { state: 'succeeded', includes: ['EVAL-193-CURRENT'] },
    memory: true,
  },
];

export const MUTATION_CASE = {
  id: 'modify-publish',
  instruction: '修改 src/main/java/Sort.java，删除导致编译失败的无用 logback import，在沙箱中编译验证；验证成功后直接自动发布新版本。不要改其他文件。',
  expect: { state: 'succeeded', tools: ['workspace.write', 'sandbox.execute', 'project.publish'], sandbox: true, revisionChanged: true },
};

export const CONTROL_CASES = [
  { id: 'rollback', kind: 'rollback' },
  { id: 'running-cancel', kind: 'running-cancel' },
  { id: 'queued-cancel-release', kind: 'queued-cancel' },
  { id: 'engine-restart-recovery', kind: 'operator-restart' },
  { id: 'sse-refresh-resume', kind: 'sse-resume' },
  { id: 'multi-conversation-concurrency', kind: 'concurrency' },
];

export const ALL_CASE_IDS = [
  ...TASK_CASES.map(({ id }) => id),
  MUTATION_CASE.id,
  ...CONTROL_CASES.map(({ id }) => id),
];

if (ALL_CASE_IDS.length !== 20) {
  throw new Error(`Expected 20 evaluation cases, received ${ALL_CASE_IDS.length}`);
}
