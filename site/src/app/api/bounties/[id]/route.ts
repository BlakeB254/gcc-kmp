export const dynamic = 'force-dynamic'

export async function GET() {
  return Response.json(
    { error: 'Bounty not found', status: 404 },
    { status: 404 }
  )
}
