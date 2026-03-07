export const dynamic = 'force-dynamic'

export async function GET() {
  // Return North Lawndale as default service area wrapped in ApiResponse
  return Response.json({
    data: {
      id: 1,
      name: 'North Lawndale',
      city: 'Chicago',
      state: 'IL',
      status: 'active',
      totalBlocks: 0,
      activeWorkers: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
    success: true,
  })
}
