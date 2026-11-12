package com.kkoemets.subscriptionautocomplete.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * A stand-in for the Claude Code CLI that runs wherever a JVM runs.
 *
 * <p>Tests start it through a real launcher script ({@code claude} on POSIX, {@code claude.cmd} on
 * Windows), so the plugin's production process code and the operating system's own argument
 * handling are both exercised. It depends on nothing but the JDK so the launcher class path is a
 * single directory.
 *
 * <p>The first argument is the fixture directory; the rest are the arguments the plugin passed.
 * Like the real CLI it rejects settings that are not valid JSON and prompt files that do not exist.
 */
public final class FakeProviderCli {
  private FakeProviderCli() {}

  public static void main(String[] argv) throws Exception {
    Path fixture = Path.of(argv[0]);
    List<String> arguments = Arrays.asList(argv).subList(1, argv.length);
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

    if (arguments.equals(List.of("--sleep"))) {
      Thread.sleep(30_000);
      return;
    }
    if (arguments.size() >= 2 && arguments.get(0).equals("auth") && arguments.get(1).equals("status")) {
      out.println(read(fixture, "auth.json"));
      System.exit(Integer.parseInt(read(fixture, "auth-exit").trim()));
    }

    Path workingDirectory = Path.of("").toAbsolutePath();
    write(fixture, "pid", Long.toString(ProcessHandle.current().pid()));
    Files.writeString(fixture.resolve("calls"), "request\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    write(fixture, "cwd", workingDirectory.toString());
    write(fixture, "arguments", String.join("\u0000", arguments) + "\u0000");
    write(fixture, "environment", env("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") + "\n"
        + env("DISABLE_AUTOUPDATER") + "\n" + env("MAX_THINKING_TOKENS") + "\n");

    if (Files.exists(fixture.resolve("reject-arguments"))) {
      // The real CLI reports an argument error without ever reading standard input.
      err.println(read(fixture, "reject-arguments").trim());
      System.exit(1);
    }

    String settings = option(arguments, "--settings");
    if (settings != null) {
      if (!settings.trim().startsWith("{")) {
        Path file = workingDirectory.resolve(settings);
        if (!Files.isRegularFile(file)) {
          err.println("Error: Settings file not found: " + file);
          System.exit(1);
        }
        settings = Files.readString(file);
      }
      // Quotes stripped by a Windows launcher leave {model:...}, which no JSON parser accepts.
      if (!settings.contains("\"model\"")) {
        err.println("Error: Invalid JSON provided to --settings");
        System.exit(1);
      }
      write(fixture, "settings", settings);
    }

    String systemPromptFile = option(arguments, "--system-prompt-file");
    String systemPrompt = option(arguments, "--system-prompt");
    if (systemPromptFile != null) {
      Path file = workingDirectory.resolve(systemPromptFile);
      if (!Files.isRegularFile(file)) {
        err.println("Error: System prompt file not found: " + file);
        System.exit(1);
      }
      systemPrompt = Files.readString(file);
    }
    if (systemPrompt != null) write(fixture, "system-prompt", systemPrompt);

    ByteArrayOutputStream input = new ByteArrayOutputStream();
    System.in.transferTo(input);
    Files.write(fixture.resolve("input"), input.toByteArray());

    for (String step : Files.readAllLines(fixture.resolve("scenario"))) {
      int space = step.indexOf(' ');
      String action = space < 0 ? step : step.substring(0, space);
      String value = space < 0 ? "" : step.substring(space + 1);
      switch (action) {
        case "out" -> out.println(value);
        case "err" -> err.println(value);
        case "child" -> write(fixture, "child", Long.toString(startSleepingChild(fixture).pid()));
        case "wait" -> Thread.sleep(30_000);
        case "exit" -> System.exit(Integer.parseInt(value.trim()));
        default -> { }
      }
    }
  }

  /** The child inherits this process's output pipes, like a tool process spawned by the real CLI. */
  private static Process startSleepingChild(Path fixture) throws IOException {
    boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    return new ProcessBuilder(
        java, "-cp", System.getProperty("java.class.path"), FakeProviderCli.class.getName(), fixture.toString(), "--sleep")
        .inheritIO()
        .start();
  }

  private static String option(List<String> arguments, String name) {
    int index = arguments.indexOf(name);
    return index >= 0 && index + 1 < arguments.size() ? arguments.get(index + 1) : null;
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value;
  }

  private static String read(Path fixture, String name) throws IOException {
    return Files.readString(fixture.resolve(name));
  }

  private static void write(Path fixture, String name, String text) throws IOException {
    Files.writeString(fixture.resolve(name), text);
  }
}
