import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // Forward /api requests to the orchestrator running on the host
      // (the docker-compose stack maps orchestrator to localhost:8080).
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
