export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { error: 'Wallet deposits not yet available', status: 501 },
    { status: 501 }
  )
}
