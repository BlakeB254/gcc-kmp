export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { error: 'Fund allocation not yet available', status: 501 },
    { status: 501 }
  )
}
