export const dynamic = 'force-dynamic'

export async function GET() {
  // Return North Lawndale as default service area
  return Response.json({
    id: 1,
    name: 'North Lawndale',
    city: 'Chicago',
    state: 'IL',
    status: 'active',
  })
}
