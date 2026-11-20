import { fileURLToPath } from 'node:url';
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
    base: '/ProfessorOS/',
    server: { fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../src/main/resources/appearance.tsv', import.meta.url))] } },
    plugins: [react(), tailwindcss()],
});
