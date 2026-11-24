package studio.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StudioApplication {
  public static void main(String[] args) {
    var application = new SpringApplication(StudioApplication.class);
    application.addInitializers(
        context -> {
          var env = context.getEnvironment();
          if (env.getProperty("spring.datasource.username", "").isBlank()
              || env.getProperty("spring.datasource.password", "").isBlank()) {
            throw new IllegalStateException(
                "Set STUDIO_DB_USERNAME and STUDIO_DB_PASSWORD before starting Agent Studio.");
          }
        });
    application.run(args);
  }
}
