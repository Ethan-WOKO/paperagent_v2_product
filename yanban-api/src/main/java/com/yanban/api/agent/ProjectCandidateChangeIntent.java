package com.yanban.api.agent;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Shared, bounded intent check for user-requested Project code/file changes. */
final class ProjectCandidateChangeIntent {

    private static final Pattern NEGATED_CHANGE_CREATION = Pattern.compile(
            "(?iu)(?:(?:\u4e0d\u8981|\u65e0\u9700|\u4e0d\u9700\u8981|\u8bf7\u52ff|\u7981\u6b62|\u4e0d\u5fc5).{0,8}"
                    + "(?:\u751f\u6210|\u521b\u5efa|\u63d0\u51fa|\u51c6\u5907|\u4fee\u6539|\u4fee\u590d|\u7f16\u8f91|\u53d8\u66f4|\u6539\u5199|\u91cd\u5199|\u6dfb\u52a0|\u65b0\u589e|\u5220\u9664|\u79fb\u9664)|"
                    + "(?:do\\s+not|don't|no\\s+need\\s+to)\\s+(?:actually\\s+)?"
                    + "(?:create|generate|propose|prepare|modify|fix|edit|change|rewrite|add|remove|delete)|"
                    + "\\bwithout\\s+(?:actually\\s+)?(?:creating|generating|proposing|preparing|modifying|editing|changing|rewriting|adding|removing|deleting)\\b)");
    private static final Pattern REQUESTED_CHANGE_ARTIFACT = Pattern.compile(
            "(?iu)(?:(?:\u63d0\u51fa|\u751f\u6210|\u521b\u5efa|\u7ed9\u51fa|\u51c6\u5907).{0,12}(?:\u5019\u9009|\u8865\u4e01)|"
                    + "\\b(?:propose|generate|create|prepare|provide|give)\\b.{0,40}\\b(?:candidate|patch)\\b)");
    private static final Pattern CODE_CHANGE_ACTION = Pattern.compile(
            "(?iu)(?:\u4fee\u6539|\u4fee\u590d|\u7f16\u8f91|\u91cd\u5199|\u6539\u5199|\u6539\u6210|\u65b0\u589e|\u6dfb\u52a0|\u52a0\u5165|\u589e\u52a0|\u5220\u9664|\u79fb\u9664|"
                    + "\\b(?:modify|fix|edit|rewrite|update|change|add|remove|delete)\\b)");
    private static final Pattern CODE_OR_FILE_TARGET = Pattern.compile(
            "(?iu)(?:\u4ee3\u7801|\u6587\u4ef6|\u811a\u672c|\u914d\u7f6e|\u8fd9\u4e2a\u7c7b|\u8be5\u7c7b|"
                    + "\\b(?:code|file|script|config(?:uration)?)\\b|\\b(?:this|that|the|java)\\s+class\\b)");

    private ProjectCandidateChangeIntent() {
    }

    static boolean requiresCandidateChange(String request) {
        if (!StringUtils.hasText(request)) return false;
        String normalized = request.toLowerCase(Locale.ROOT);
        if (NEGATED_CHANGE_CREATION.matcher(normalized).find()) return false;
        if (REQUESTED_CHANGE_ARTIFACT.matcher(normalized).find()) return true;
        boolean changeAction = CODE_CHANGE_ACTION.matcher(normalized).find();
        boolean codeOrFileTarget = !ProjectMaterialScope.explicitRelativePaths(request).isEmpty()
                || CODE_OR_FILE_TARGET.matcher(normalized).find();
        return changeAction && codeOrFileTarget;
    }
}
