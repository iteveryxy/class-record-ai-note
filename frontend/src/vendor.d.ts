declare module '@vscode/markdown-it-katex' {
  import type { PluginWithOptions } from 'markdown-it'
  const plugin: PluginWithOptions<Record<string, unknown>>
  export default plugin
}
