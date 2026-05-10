export const dynamic = 'force-dynamic'

export async function POST() {
  return Response.json(
    { data: null, success: false, message: 'Paid bounty claiming is not yet available' },
    { status: 501 }
  )
}
