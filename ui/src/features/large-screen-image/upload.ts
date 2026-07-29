export const MAX_REFERENCE_IMAGE_BYTES = 30 * 1024 * 1024

export type ReferenceFileValidation =
  | { ok: true; file: File }
  | { ok: false; code: 'EMPTY' | 'MULTIPLE_IMAGES' | 'NOT_IMAGE' | 'TOO_LARGE' }

export function validateReferenceFiles(files: File[]): ReferenceFileValidation {
  if (files.length === 0) return { ok: false, code: 'EMPTY' }
  if (files.length !== 1) return { ok: false, code: 'MULTIPLE_IMAGES' }
  const file = files[0]
  if (!file) return { ok: false, code: 'EMPTY' }
  if (!file.type.startsWith('image/')) return { ok: false, code: 'NOT_IMAGE' }
  if (file.size > MAX_REFERENCE_IMAGE_BYTES) return { ok: false, code: 'TOO_LARGE' }
  return { ok: true, file }
}
