import { randomBytes } from 'node:crypto';
import { defineConfig } from '@playwright/test';

const backend = 'http://127.0.0.1:18181';
const frontend = 'http://127.0.0.1:14173';

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: {
    baseURL: frontend,
    browserName: 'chromium',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH },
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'java -jar ../backend/target/agent-studio-backend-0.1.0-SNAPSHOT.jar',
      url: backend + '/api/studio/meeting-notes/draft',
      env: {
        SERVER_PORT: '18181',
        STUDIO_DB_URL: 'jdbc:h2:mem:browser;MODE=MySQL;DATABASE_TO_LOWER=TRUE',
        STUDIO_DB_USERNAME: 'studio_test',
        STUDIO_DB_PASSWORD: randomBytes(24).toString('hex'),
        STUDIO_LIVE_ENABLED: 'false',
      },
      reuseExistingServer: false,
      timeout: 90000,
    },
    {
      command: 'npm run preview -- --host 127.0.0.1 --port 14173 --strictPort',
      url: frontend,
      env: { STUDIO_API_PORT: '18181' },
      reuseExistingServer: false,
    },
  ],
});
