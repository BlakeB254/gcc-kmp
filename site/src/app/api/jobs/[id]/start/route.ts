export const dynamic = 'force-dynamic'

export async function PATCH() {
  return Response.json(
    { error: 'Job start not yet available', status: 501 },
    { status: 501 }
  )
}
