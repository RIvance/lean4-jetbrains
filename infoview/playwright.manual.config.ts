import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './manual-tests',
    timeout: 45_000,
    workers: 1,
    use: {
        browserName: 'chromium',
        viewport: { width: 1000, height: 760 },
        screenshot: 'only-on-failure',
        launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }
    }
});
