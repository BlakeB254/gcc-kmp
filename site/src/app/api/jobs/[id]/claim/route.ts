export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { error: 'Job claiming not yet available', status: 501 },
    { status: 501 }
  )
}
