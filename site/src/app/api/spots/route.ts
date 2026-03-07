export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { error: 'Spot reporting not yet available via this endpoint', status: 501 },
    { status: 501 }
  )
}
