import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In development the website runs on port 5173 and the Spring API on port 8080.
// The proxy forwards /api/search → http://localhost:8080/search, so the browser only ever talks to one
// origin and we need no cross-origin (CORS) setup.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
  test: {
    environment: "jsdom", // a fake browser, so components can render in tests
  },
});
