import MarkdownIt from 'markdown-it'
import katex from '@vscode/markdown-it-katex'

// Disable HTML. markdown-it rejects unsafe URL schemes; KaTeX's trust is false.
const renderer = new MarkdownIt({ html: false, linkify: true, breaks: true }).use(katex, { throwOnError: false, trust: false })
export const renderMarkdown = (source: string) => renderer.render(source)
export function time(ms = 0) {
  const total = Math.floor(ms / 1000)
  const hours = Math.floor(total / 3600)
  return (hours ? `${String(hours).padStart(2, '0')}:` : '') + `${String(Math.floor(total / 60) % 60).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}
export function size(bytes: number) { return `${(bytes / 1024 / 1024).toFixed(1)} MB` }
