const GENERATED_IMAGE_RE = /!\[large-screen-image\]\(([^\s)]+)\)/g
const TRUSTED_OUTPUT_HOST = 'k-devs.tos-cn-guangzhou.volces.com'

export interface GeneratedImage {
  imageUrl: string
}

function isTrustedOutputUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === TRUSTED_OUTPUT_HOST && url.pathname.length > 1
  } catch {
    return false
  }
}

export function parseGeneratedImages(content: string): GeneratedImage[] {
  for (const match of content.matchAll(GENERATED_IMAGE_RE)) {
    const imageUrl = match[1]
    if (imageUrl && isTrustedOutputUrl(imageUrl)) return [{ imageUrl }]
  }
  return []
}
