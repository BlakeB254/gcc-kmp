export const dynamic = 'force-dynamic'

export async function GET() {
  return Response.json(
    { error: 'Job not found', status: 404 },
    { status: 404 }
  )
}
