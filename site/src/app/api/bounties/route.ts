export const dynamic = 'force-dynamic'

export async function GET() {
  return Response.json({ data: [], success: true })
}

export async function POST() {
  return Response.json(
    { error: 'Bounty creation not yet available', status: 501 },
    { status: 501 }
  )
}
