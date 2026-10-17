import { describe, expect, it } from 'vitest'
import { renderMarkdown, time } from './markdown'

describe('笔记预览', () => {
  it('原始 HTML 和脚本不得进入预览 DOM', () => {
    const html = renderMarkdown('<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n[x](javascript:alert(1))')
    expect(html).not.toContain('<script>')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href="javascript:')
    expect(html).toContain('&lt;script&gt;')
  })
  it('渲染数学公式，且不允许 KaTeX 信任任意 HTML', () => {
    expect(renderMarkdown("$$f'(x)=\\lim_{h\\to0}\\frac{f(x+h)-f(x)}h$$")).toContain('katex')
    expect(renderMarkdown('$\\href{javascript:alert(1)}{bad}$')).not.toContain('href="javascript:')
  })
  it('长课堂时长显示小时', () => {
    expect(time(3661000)).toBe('01:01:01')
    expect(time(0)).toBe('00:00')
  })
})
