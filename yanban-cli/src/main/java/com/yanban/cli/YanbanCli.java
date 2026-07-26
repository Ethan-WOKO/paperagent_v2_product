package com.yanban.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Console;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Scanner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "yanban", mixinStandardHelpOptions = true, subcommands = {
        YanbanCli.LoginCommand.class,
        YanbanCli.ChatCommand.class,
        YanbanCli.ConfigCommand.class,
        YanbanCli.KbCommand.class,
        YanbanCli.PaperCommand.class
})
public class YanbanCli implements Runnable {

    static final CliConfigStore CONFIG_STORE = new CliConfigStore();
    static final CliApiClient API = new CliApiClient(CONFIG_STORE);
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new YanbanCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        System.out.println("Yanban CLI，请使用子命令，如 login / chat / config / kb / paper");
    }

    @Command(name = "login", mixinStandardHelpOptions = true)
    static class LoginCommand implements Runnable {
        @Option(names = "--api-base-url", defaultValue = "http://localhost:8080") String apiBaseUrl;

        @Override
        public void run() {
            Console console = System.console();
            Scanner scanner = console == null ? new Scanner(System.in) : null;
            String username = console != null ? console.readLine("username: ") : prompt(scanner, "username: ");
            String password = console != null ? new String(console.readPassword("password: ")) : prompt(scanner, "password: ");
            JsonNode response = API.login(apiBaseUrl, username, password);
            Properties properties = CONFIG_STORE.load();
            properties.setProperty("apiBaseUrl", apiBaseUrl);
            properties.setProperty("accessToken", response.path("accessToken").asText());
            properties.setProperty("refreshToken", response.path("refreshToken").asText(""));
            CONFIG_STORE.save(properties);
            System.out.println("登录成功，配置已写入: " + CONFIG_STORE.getConfigPath());
        }

        private String prompt(Scanner scanner, String label) {
            System.out.print(label);
            return scanner.nextLine();
        }
    }

    @Command(name = "chat", mixinStandardHelpOptions = true)
    static class ChatCommand implements Runnable {
        @Option(names = "--title", defaultValue = "CLI 会话") String title;

        @Override
        public void run() {
            JsonNode session = API.createSession(title);
            long sessionId = session.path("id").asLong();
            System.out.println("已创建会话 #" + sessionId + "，输入 exit 退出。");
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("you> ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                System.out.print("assistant> ");
                API.chatViaWebSocket(sessionId, line);
            }
        }
    }

    @Command(name = "config", mixinStandardHelpOptions = true, subcommands = {ConfigListCommand.class, ConfigSetCommand.class})
    static class ConfigCommand implements Runnable { public void run() {} }

    @Command(name = "list")
    static class ConfigListCommand implements Runnable {
        @Override public void run() {
            JsonNode settings = API.getSettings();
            System.out.println(settings.toPrettyString());
        }
    }

    @Command(name = "set")
    static class ConfigSetCommand implements Runnable {
        @Parameters(index = "0") String key;
        @Parameters(index = "1") String value;

        @Override public void run() {
            JsonNode current = API.getSettings();
            var node = OBJECT_MAPPER.createObjectNode();
            node.put("defaultProvider", current.path("defaultProvider").asText("deepseek"));
            node.put("deepseekModel", current.path("deepseekModel").asText("deepseek-v4-flash"));
            node.put("glmModel", current.path("glmModel").asText("glm-4.5-air"));
            node.put("deepseekTemperature", current.path("deepseekTemperature").asDouble(0.7));
            node.put("maxSteps", current.path("maxSteps").asInt(20));
            node.put("ragDefaultEnabled", current.path("ragDefaultEnabled").asBoolean(true));
            switch (key) {
                case "max-steps" -> node.put("maxSteps", Integer.parseInt(value));
                case "default-provider" -> node.put("defaultProvider", value);
                default -> throw new IllegalArgumentException("暂不支持的 key: " + key);
            }
            System.out.println(API.updateSettings(node).toPrettyString());
        }
    }

    @Command(name = "kb", mixinStandardHelpOptions = true, subcommands = {KbListCommand.class, KbUploadCommand.class})
    static class KbCommand implements Runnable { public void run() {} }

    @Command(name = "list")
    static class KbListCommand implements Runnable {
        @Override public void run() {
            JsonNode documents = API.listKbDocuments();
            documents.forEach(item -> System.out.println(item.path("id").asLong() + "\t" + item.path("status").asText() + "\t" + item.path("filename").asText()));
        }
    }

    @Command(name = "upload")
    static class KbUploadCommand implements Runnable {
        @Parameters(index = "0") Path file;
        @Option(names = "--public", defaultValue = "false") boolean isPublic;

        @Override public void run() {
            System.out.println(API.simpleUpload(file, isPublic).toPrettyString());
        }
    }

    @Command(name = "paper", mixinStandardHelpOptions = true, subcommands = {PaperStatusCommand.class})
    static class PaperCommand implements Runnable { public void run() {} }

    @Command(name = "status")
    static class PaperStatusCommand implements Runnable {
        @Parameters(index = "0") long taskId;

        @Override public void run() {
            JsonNode task = API.getPaperTask(taskId);
            System.out.println("taskId=" + task.path("id").asLong());
            System.out.println("status=" + task.path("status").asText());
            System.out.println("stage=" + task.path("currentStage").asText("-"));
            System.out.println("recentLog=" + task.path("currentStage").asText("-"));
        }
    }
}
