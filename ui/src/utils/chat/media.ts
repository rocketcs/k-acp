export const IMAGE_EXTENSIONS = new Set([
  'jpg',
  'jpeg',
  'png',
  'gif',
  'bmp',
  'webp',
  'svg',
  'ico',
])

export function normalizeMediaExtension(extension: string | undefined): string {
  return (extension ?? '').trim().replace(/^\./, '').toLowerCase()
}

export function isImageExtension(extension: string | undefined): boolean {
  return IMAGE_EXTENSIONS.has(normalizeMediaExtension(extension))
}

export function getMediaMimeType(extension: string | undefined): string | undefined {
  switch (normalizeMediaExtension(extension)) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg'
    case 'png':
      return 'image/png'
    case 'gif':
      return 'image/gif'
    case 'bmp':
      return 'image/bmp'
    case 'webp':
      return 'image/webp'
    case 'svg':
      return 'image/svg+xml'
    case 'ico':
      return 'image/x-icon'
    default:
      return undefined
  }
}
