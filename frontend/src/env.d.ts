/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_UI_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
