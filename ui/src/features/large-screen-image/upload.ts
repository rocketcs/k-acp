export type ReferenceFileValidation =
  | { ok: true; file: File }
  | { ok: false; code: 'EMPTY' | 'MULTIPLE_IMAGES' | 'NOT_IMAGE' }

export function validateReferenceFiles(files: File[]): ReferenceFileValidation {
  if (files.length === 0) {
    return { ok: false, code: 'EMPTY' }
  }
  if (files.length !== 1) {
    return { ok: false, code: 'MULTIPLE_IMAGES' }
  }
  const file = files[0]
  if (!file) {
    return { ok: false, code: 'EMPTY' }
  }
  if (!file.type.startsWith('image/')) {
    return { ok: false, code: 'NOT_IMAGE' }
  }
  return { ok: true, file }
}
