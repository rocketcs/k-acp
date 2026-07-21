/**
 * 模型供应商品牌Logo智能匹配
 * 根据baseUrl智能匹配对应的品牌logo，找不到则使用默认logo
 *
 * @author huxuehao
 */

import modelProviderDefault from '@/assets/avatar/model-provider.png'

// 品牌logo导入
import openaiLogo from '@/assets/brand/openai.png'
import anthropicLogo from '@/assets/brand/anthropic.png'
import geminiLogo from '@/assets/brand/gemini-color.png'
import deepseekLogo from '@/assets/brand/deepseek-color.png'
import bailianLogo from '@/assets/brand/bailian-color.png'
import qwenLogo from '@/assets/brand/qwen-color.png'
import doubaoLogo from '@/assets/brand/doubao-color.png'
import bytedanceLogo from '@/assets/brand/bytedance-color.png'
import hunyuanLogo from '@/assets/brand/hunyuan-color.png'
import zhipuLogo from '@/assets/brand/zhipu-color.png'
import moonshotLogo from '@/assets/brand/moonshot.png'
import grokLogo from '@/assets/brand/grok.png'
import minimaxLogo from '@/assets/brand/minimax-color.png'
import huggingfaceLogo from '@/assets/brand/huggingface-color.png'
import openrouterLogo from '@/assets/brand/openrouter.png'
import copilotLogo from '@/assets/brand/copilot-color.png'
import jinaLogo from '@/assets/brand/jina.png'
import metaaiLogo from '@/assets/brand/metaai-color.png'
import ollamaLogo from '@/assets/brand/ollama.png'
import jimengLogo from '@/assets/brand/jimeng-color.png'
import klingLogo from '@/assets/brand/kling-color.png'
import lumaLogo from '@/assets/brand/luma-color.png'
import xiaomimimoLogo from '@/assets/brand/xiaomimimo.png'

/**
 * 从baseUrl中提取主机名（不含协议、端口和路径）
 */
function extractHost(url: string): string {
  if (!url) return ''
  let host = url.trim()
  host = host.replace(/^https?:\/\//, '')
  const pathIdx = host.indexOf('/')
  if (pathIdx !== -1) {
    host = host.substring(0, pathIdx)
  }
  const portIdx = host.lastIndexOf(':')
  if (portIdx !== -1) {
    host = host.substring(0, portIdx)
  }
  return host.toLowerCase()
}

/**
 * 从baseUrl中提取端口号
 */
function extractPort(url: string): number | null {
  if (!url) return null
  let host = url.trim()
  host = host.replace(/^https?:\/\//, '')
  const pathIdx = host.indexOf('/')
  if (pathIdx !== -1) {
    host = host.substring(0, pathIdx)
  }
  const portIdx = host.lastIndexOf(':')
  if (portIdx !== -1) {
    const port = parseInt(host.substring(portIdx + 1), 10)
    return isNaN(port) ? null : port
  }
  return null
}

/**
 * 匹配规则：按优先级排列，越靠前优先级越高
 * 每条规则包含一组关键词，host包含任一关键词即匹配
 */
interface MatchRule {
  keywords: string[]
  logo: string
}

const matchRules: MatchRule[] = [
  // Ollama 自部署服务
  { keywords: ['ollama'], logo: ollamaLogo },
  // OpenAI
  { keywords: ['openai.com', 'openai'], logo: openaiLogo },
  // Anthropic Claude
  { keywords: ['anthropic.com', 'anthropic'], logo: anthropicLogo },
  // Google Gemini
  { keywords: ['gemini', 'generativelanguage.googleapis.com'], logo: geminiLogo },
  // DeepSeek
  { keywords: ['deepseek'], logo: deepseekLogo },
  // 阿里云百炼 DashScope
  { keywords: ['dashscope', 'bailian', 'aliyuncs.com'], logo: bailianLogo },
  // 通义千问 Qwen
  { keywords: ['qwen'], logo: qwenLogo },
  // 字节豆包 Doubao（火山引擎）
  { keywords: ['doubao', 'volces'], logo: doubaoLogo },
  // 字节跳动 ByteDance
  { keywords: ['bytedance'], logo: bytedanceLogo },
  // 腾讯混元 Hunyuan
  { keywords: ['hunyuan'], logo: hunyuanLogo },
  // 智谱 AI GLM
  { keywords: ['zhipu', 'bigmodel'], logo: zhipuLogo },
  // 月之暗面 Moonshot / Kimi
  { keywords: ['moonshot'], logo: moonshotLogo },
  // xAI Grok
  { keywords: ['grok', 'x.ai'], logo: grokLogo },
  // MiniMax
  { keywords: ['minimax'], logo: minimaxLogo },
  // HuggingFace
  { keywords: ['huggingface'], logo: huggingfaceLogo },
  // OpenRouter
  { keywords: ['openrouter'], logo: openrouterLogo },
  // Microsoft Copilot
  { keywords: ['copilot', 'github.com'], logo: copilotLogo },
  // Jina AI
  { keywords: ['jina'], logo: jinaLogo },
  // Meta AI / Llama
  { keywords: ['meta.ai', 'meta'], logo: metaaiLogo },
  // 即梦 Jimeng（字节旗下）
  { keywords: ['jimeng'], logo: jimengLogo },
  // 可灵 Kling（快手旗下）
  { keywords: ['kling'], logo: klingLogo },
  // Luma AI
  { keywords: ['luma'], logo: lumaLogo },
  // 小米MIMO XIAOMIMIMO
  { keywords: ['xiaomimimo'], logo: xiaomimimoLogo },
]

/**
 * 根据baseUrl智能匹配模型供应商品牌logo
 * 匹配优先级：端口号11434 → Ollama > 域名关键词匹配 > 默认logo
 *
 * @param baseUrl 模型供应商的baseUrl
 * @returns 匹配到的品牌logo路径，未匹配返回默认logo
 */
export function getProviderLogo(baseUrl: string): string {
  if (!baseUrl) return modelProviderDefault

  const host = extractHost(baseUrl)
  const port = extractPort(baseUrl)

  // 特殊规则：端口11434 → Ollama 本地部署
  if (port === 11434) {
    return ollamaLogo
  }

  // 按优先级匹配域名关键词
  for (const rule of matchRules) {
    for (const keyword of rule.keywords) {
      if (host.includes(keyword)) {
        return rule.logo
      }
    }
  }

  return modelProviderDefault
}
