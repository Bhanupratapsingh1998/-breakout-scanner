import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Builds straight into Spring Boot's static resources so the backend jar
// serves the frontend from the same port — no separate dev server needed.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
