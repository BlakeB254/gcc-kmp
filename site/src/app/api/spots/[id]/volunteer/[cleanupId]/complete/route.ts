export const dynamic = 'force-dynamic'

export async function PATCH() {
  return Response.json(
    { error: 'Cleanup completion not yet available', status: 501 },
    { status: 501 }
  )
}
