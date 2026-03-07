export const dynamic = 'force-dynamic'

export async function GET() {
  return Response.json({
    data: {
      id: 0,
      sponsorId: null,
      workerId: null,
      totalBalance: 0,
      generalFundBalance: 0,
      routeFundBalance: 0,
      bountyFundBalance: 0,
      totalDeposited: 0,
      totalSpent: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
    success: true,
  })
}
