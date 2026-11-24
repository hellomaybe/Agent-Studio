package studio.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2DurabilityTest {
  @Test
  void acknowledgedCommitSurvivesImmediateLossOfAProcess(@TempDir Path directory) throws Exception {
    var properties = new Properties();
    try (var config = getClass().getResourceAsStream("/application.properties")) {
      properties.load(config);
    }
    String setting = properties.getProperty("spring.datasource.url");
    String url =
        setting
            .substring(setting.indexOf("jdbc:h2:"), setting.length() - 1)
            .replace("./data/agent-studio", directory.resolve("workspace").toString());
    var driver = Class.forName("org.h2.Driver");
    String classpath =
        Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(driver.getProtectionDomain().getCodeSource().getLocation().toURI());
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var writer =
        new ProcessBuilder(java, "-cp", classpath, InterruptedWriter.class.getName(), url)
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(writer.waitFor(15, TimeUnit.SECONDS));
      assertEquals(0, writer.exitValue(), new String(writer.getInputStream().readAllBytes()));
    } finally {
      if (writer.isAlive()) writer.destroyForcibly();
    }
    try (var restored = DriverManager.getConnection(url, "sa", "");
        var stmt = restored.createStatement();
        var rows = stmt.executeQuery("SELECT revision FROM receipt")) {
      assertTrue(rows.next());
      assertEquals(2, rows.getInt(1));
    }
  }

  public static class InterruptedWriter {
    public static void main(String[] args) throws Exception {
      var connection = DriverManager.getConnection(args[0], "sa", "");
      var statement = connection.createStatement();
      statement.execute("CREATE TABLE receipt(revision BIGINT NOT NULL)");
      connection.setAutoCommit(false);
      statement.executeUpdate("INSERT INTO receipt VALUES (2)");
      connection.commit();
      // Simulate a lost JVM immediately after a successful commit without running shutdown hooks.
      Runtime.getRuntime().halt(0);
    }
  }
}
