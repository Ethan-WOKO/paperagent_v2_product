package com.yanban.api.agent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Narrow server-side boundary for Project code requests that require governed execution. */
final class ProjectSandboxExecutionIntent {

    private static final Pattern EXECUTION_ACTION = Pattern.compile(
            "(?iu)(?:\u8fd0\u884c|\u6267\u884c|\u7f16\u8bd1|\u6784\u5efa|\u6d4b\u8bd5(?!\u77e9\u9635|\u7ed3\u679c|\u62a5\u544a|\u6570\u636e|\u6307\u6807|\u7528\u4f8b)|"
                    + "\\b(?:run|execute|compile|build|test)\\b)");
    private static final Pattern CODE_TARGET = Pattern.compile(
            "(?iu)(?:\u7a0b\u5e8f|\u4ee3\u7801|\u6e90\u7801|\u811a\u672c|\\b(?:program|code|source|script|application|app)\\b)");
    private static final Pattern NEGATED_ACTION_PREFIX = Pattern.compile(
            "(?iu)(?:\u4e0d\u8981|\u65e0\u9700|\u65e0\u987b|\u4e0d\u9700\u8981|\u4e0d\u7528|\u7981\u6b62|\u522b|\u8bf7\u52ff|"
                    + "do\\s+not|don't|without|never|no\\s+need\\s+to|must\\s+not|should\\s+not)"
                    + "\\s*(?:\u5b9e\u9645|\u771f\u5b9e|\u4efb\u4f55|\u8fdb\u884c)?\\s*(?:\u4ee3\u7801|\u547d\u4ee4|\u7a0b\u5e8f|\u6c99\u7bb1)?\\s*$");
    private static final Pattern COORDINATED_NEGATION_BRIDGE = Pattern.compile(
            "(?iu)^[\\s,，、/]*(?:or|nor|and|\u6216(?:\u8005)?|\u548c|\u4ee5\u53ca|\u5e76(?:\u4e14)?)[\\s,，、/]*$");
    private static final Set<String> EXECUTABLE_SOURCE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".c", ".cc", ".cpp", ".cxx",
            ".cs", ".go", ".rs", ".kt", ".kts", ".scala", ".sh", ".ps1");

    private ProjectSandboxExecutionIntent() {
    }

    static boolean requiresGovernedExecution(String request) {
        if (!mentionsExecutionAction(request)) return false;
        if (CODE_TARGET.matcher(request).find()) return true;
        return ProjectMaterialScope.explicitRelativePathsPreservingCase(request).stream()
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(path -> EXECUTABLE_SOURCE_EXTENSIONS.stream().anyMatch(path::endsWith));
    }

    static boolean mentionsExecutionAction(String text) {
        if (!StringUtils.hasText(text)) return false;
        Matcher action = EXECUTION_ACTION.matcher(text);
        boolean previousActionNegated = false;
        int previousActionEnd = -1;
        while (action.find()) {
            String prefix = text.substring(Math.max(0, action.start() - 32), action.start());
            boolean negated = NEGATED_ACTION_PREFIX.matcher(prefix).find();
            if (!negated && previousActionNegated && previousActionEnd >= 0) {
                String bridge = text.substring(previousActionEnd, action.start());
                negated = COORDINATED_NEGATION_BRIDGE.matcher(bridge).matches();
            }
            if (!negated) return true;
            previousActionNegated = true;
            previousActionEnd = action.end();
        }
        return false;
    }
}
