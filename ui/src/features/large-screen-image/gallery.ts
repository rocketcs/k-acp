const GENERATED_IMAGE_RE = /!\[large-screen-image\]\((https:\/\/[^\s)]+)\)/g

export interface GeneratedImage {
  imageUrl: string
}

export function parseGeneratedImages(content: string): GeneratedImage[] {
  return Array.from(content.matchAll(GENERATED_IMAGE_RE))
    .map((match) => match[1])
    .filter((imageUrl): imageUrl is string => Boolean(imageUrl))
    .map((imageUrl) => ({ imageUrl }))
}
