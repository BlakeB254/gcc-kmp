import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blockProposals, blocks, blockExclusionZones } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { generateInnerBoundaryFromRing } from '@/lib/inner-boundary-generator'

interface ProposalExclusionZone {
  polygon: { type: 'Polygon'; coordinates: [number, number][][] }
  label?: string
}

// North Lawndale — the only service area this generator currently targets.
const NORTH_LAWNDALE_SERVICE_AREA_ID = 1

export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const { id } = await params
  const proposalId = parseInt(id, 10)
  if (isNaN(proposalId)) {
    return NextResponse.json({ error: 'Invalid proposal id' }, { status: 400 })
  }

  try {
    const [proposal] = await db.select().from(blockProposals).where(eq(blockProposals.id, proposalId)).limit(1)
    if (!proposal) {
      return NextResponse.json({ error: 'Proposal not found' }, { status: 404 })
    }
    if (proposal.status !== 'pending') {
      return NextResponse.json({ error: `Proposal already ${proposal.status}` }, { status: 409 })
    }

    const [newBlock] = await db
      .insert(blocks)
      .values({
        code: proposal.codeHint,
        name: proposal.name,
        serviceAreaId: NORTH_LAWNDALE_SERVICE_AREA_ID,
        locationNeighborhood: proposal.locationNeighborhood,
        locationBoundary: proposal.locationBoundary,
        locationCenter: proposal.locationCenter,
        streetName: proposal.streetName,
        fromCorner: proposal.fromCorner,
        toCorner: proposal.toCorner,
        side: proposal.side,
        status: 'available',
      })
      .returning({ id: blocks.id })

    const zones = (proposal.exclusionZones as ProposalExclusionZone[] | null) || []
    if (zones.length > 0) {
      await db.insert(blockExclusionZones).values(
        zones.map((z) => ({
          blockId: newBlock.id,
          polygon: z.polygon,
          label: z.label || 'building',
          createdByAdminId: auth.workerId,
        }))
      )
    }

    // Also compute the property-conforming inner boundary (same algorithm
    // the route-v2 admin regenerate action uses) so this block matches the
    // app's interior/exterior rendering from the moment it's published,
    // not just its per-building exclusion zones. Best-effort: a live
    // Overpass call can fail or time out, and that shouldn't block
    // publishing the block itself — the admin can retry later via the
    // existing regenerate-inner-boundary action once this block has a
    // paired route.
    let innerBoundaryGenerated = false
    try {
      const ring = (proposal.locationBoundary as { coordinates?: number[][][] })?.coordinates?.[0]
      if (ring) {
        const { multiPolygon } = await generateInnerBoundaryFromRing(ring)
        await db.update(blocks).set({ innerBoundary: multiPolygon }).where(eq(blocks.id, newBlock.id))
        innerBoundaryGenerated = true
      }
    } catch (error) {
      console.error('[block-proposals/approve] inner boundary generation failed, block published without it:', error)
    }

    await db
      .update(blockProposals)
      .set({
        status: 'approved',
        reviewedByAdminId: auth.workerId,
        reviewedAt: new Date().toISOString(),
        publishedBlockId: newBlock.id,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(blockProposals.id, proposalId))

    return NextResponse.json({ blockId: newBlock.id, exclusionZonesCreated: zones.length, innerBoundaryGenerated })
  } catch (error) {
    console.error('Error approving block proposal:', error)
    return NextResponse.json({ error: 'Failed to approve block proposal' }, { status: 500 })
  }
}
