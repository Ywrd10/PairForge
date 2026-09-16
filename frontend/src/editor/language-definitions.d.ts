// Monaco ships these definition modules without declarations. Their exported
// configuration and tokenizer use Monaco's public types.
declare module 'monaco-editor/languages/definitions/java/java' {
  export const conf: import('monaco-editor/editor/editor.api').languages.LanguageConfiguration
  export const language: import('monaco-editor/editor/editor.api').languages.IMonarchLanguage
}
declare module 'monaco-editor/languages/definitions/python/python' {
  export const conf: import('monaco-editor/editor/editor.api').languages.LanguageConfiguration
  export const language: import('monaco-editor/editor/editor.api').languages.IMonarchLanguage
}
