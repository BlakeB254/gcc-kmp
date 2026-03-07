import { apiSuccess } from '@/lib/api-helpers'

export async function GET() {
  return apiSuccess({
    status: 'ok',
    service: 'gcc-api',
    version: '0.1.0',
  })
}
