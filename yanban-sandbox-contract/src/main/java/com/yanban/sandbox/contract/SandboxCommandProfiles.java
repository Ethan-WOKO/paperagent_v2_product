package com.yanban.sandbox.contract;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Shared server-owned command profiles; neither API nor Broker accepts shell strings. */
public final class SandboxCommandProfiles {
    private SandboxCommandProfiles() { }
    public static void requireAllowed(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.size() > 64 || argv.stream().anyMatch(SandboxCommandProfiles::invalid)
                || !matches(argv)) throw new IllegalArgumentException("command profile is not allowed");
    }
    /**
     * Returns a server-owned dependency preparation command for a governed
     * project profile. The client can never supply this argv directly.
     */
    public static Optional<List<List<String>>> dependencyPreparation(List<String> argv) {
        requireAllowed(argv);
        if (javaDependencies(argv)) {
            List<String> preparation = new java.util.ArrayList<>();
            preparation.add("yanban-java-dependencies");
            argv.subList(3, argv.size()).forEach(value ->
                    preparation.add(value.substring("--dependency=".length())));
            return Optional.of(List.of(List.copyOf(preparation)));
        }
        if (!"mvn".equals(argv.get(0))) return Optional.empty();
        return Optional.of(List.of(
                List.of(
                        "mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.codehaus.plexus:plexus-utils:1.1"),
                List.of(
                        "mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.2"),
                List.of(
                        "mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.junit.platform:junit-platform-launcher:1.9.3"),
                List.of(
                        "mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.junit.platform:junit-platform-launcher:1.11.4"),
                List.of(
                        "mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline")));
    }
    private static boolean matches(List<String> argv){return switch(argv.get(0)){
        case "mvn" -> maven(argv); case "java" -> argv.equals(List.of("java","-version"))
                || argv.size()==2&&source(argv.get(1));
        case "javac" -> argv.size()>=2&&argv.size()<=33&&argv.subList(1,argv.size()).stream().allMatch(SandboxCommandProfiles::source);
        case "yanban-runner" -> runner(argv);
        case "git" -> argv.equals(List.of("git","diff","--check"))||argv.equals(List.of("git","status","--short"))
                ||argv.equals(List.of("git","rev-parse","--verify","HEAD")); default -> false;};}
    private static boolean maven(List<String> argv){if(argv.size()<2||argv.size()>8)return false;boolean goal=false;for(int i=1;i<argv.size();i++){String arg=argv.get(i);if("test".equals(arg)||"verify".equals(arg)){if(goal)return false;goal=true;continue;}if(Set.of("-o","-q","-am").contains(arg))continue;if("-pl".equals(arg)&&i+1<argv.size()&&modules(argv.get(++i)))continue;return false;}return goal;}
    private static boolean modules(String value){for(String module:value.split(",",-1))if(!module.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))return false;return true;}
    private static boolean runner(List<String> argv){
        if(argv.size()<3)return false;
        return switch(argv.get(1)){
            case "java" -> source(argv.get(2)) && javaDependencyArguments(argv);
            case "python" -> argv.size()==3&&source(argv.get(2),".py");
            case "c" -> argv.size()==3&&source(argv.get(2),".c");
            case "cpp" -> argv.size()==3&&(source(argv.get(2),".cc")||source(argv.get(2),".cpp")||source(argv.get(2),".cxx"));
            default -> false;
        };
    }
    public static boolean usesJavaDependencies(List<String> argv) {
        requireAllowed(argv);
        return javaDependencies(argv);
    }
    private static boolean javaDependencies(List<String> argv) {
        return argv != null && argv.size() > 3 && "yanban-runner".equals(argv.get(0))
                && "java".equals(argv.get(1)) && javaDependencyArguments(argv);
    }
    private static boolean javaDependencyArguments(List<String> argv) {
        if (argv.size()==3) return true;
        if (argv.size()>3+JavaMavenCoordinates.MAX_COORDINATES) return false;
        List<String> coordinates=new java.util.ArrayList<>();
        for(int i=3;i<argv.size();i++){
            String value=argv.get(i);
            if(!value.startsWith("--dependency="))return false;
            coordinates.add(value.substring("--dependency=".length()));
        }
        try{return !JavaMavenCoordinates.normalize(coordinates).isEmpty();}
        catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean source(String value){try{Path path=Path.of(value);return !path.isAbsolute()&&path.normalize().equals(path)&&value.endsWith(".java")&&!value.contains("\\")&&!value.startsWith(".");}catch(RuntimeException ex){return false;}}
    private static boolean source(String value,String suffix){try{Path path=Path.of(value);return !path.isAbsolute()&&path.normalize().equals(path)&&value.endsWith(suffix)&&!value.contains("\\")&&!value.startsWith(".");}catch(RuntimeException ex){return false;}}
    private static boolean invalid(String value){return value==null||value.isBlank()||value.length()>4096||value.indexOf('\0')>=0||value.contains("\r")||value.contains("\n");}
}
