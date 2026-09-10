// API Configuration
// If VITE_API_BASE is set, use it.
// In production (e.g. bundled static assets in Spring Boot on Render/Docker), default to relative path ""
// In local Vite dev mode, default to "http://localhost:8080"
export const API_BASE =
  import.meta.env.VITE_API_BASE !== undefined
    ? import.meta.env.VITE_API_BASE
    : import.meta.env.DEV
    ? "http://localhost:8080"
    : "";
