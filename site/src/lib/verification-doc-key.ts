/**
 * Private R2 object key for a verification document uploaded from the web
 * documents page. Lives under the same `verification/{workerId}/` prefix as
 * the mobile upload route, so the admin presign routes, the retention sweep
 * and account deletion all find it.
 */
export type VerificationDocumentType = 'government_id' | 'proof_of_address' | 'address_change'

export function verificationDocKey(
  workerId: number,
  documentType: VerificationDocumentType,
  ext: 'jpg' | 'pdf',
  timestamp: number = Date.now(),
): string {
  if (!Number.isInteger(workerId) || workerId <= 0) {
    throw new Error(`invalid workerId: ${workerId}`)
  }
  return `verification/${workerId}/${documentType}-${timestamp}.${ext}`
}
