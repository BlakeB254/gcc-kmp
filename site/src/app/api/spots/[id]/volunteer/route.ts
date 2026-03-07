export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { error: 'Volunteer cleanup not yet available', status: 501 },
    { status: 501 }
  )
}
