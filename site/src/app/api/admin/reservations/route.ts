import { NextResponse } from 'next/server'
import { desc, eq, isNotNull } from 'drizzle-orm'
import { z } from 'zod'
import { db } from '@/db'
import { sponsors, transactions } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'

// Block reservations: sponsorships paid while the blocks could not be assigned
// (see lib/sponsor-checkout.ts). This screen is a ledger of what is owed to
// sponsors. Marking one allocated records that an admin has scheduled the
// cleaning; it does not release a block to workers — that stays a deliberate
// step in Routes & Blocks.

export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const rows = await db
    .select({
      id: transactions.id,
      amount: transactions.amount,
      status: transactions.status,
      sponsorType: transactions.sponsorType,
      reservedBlockCount: transactions.reservedBlockCount,
      reservedBlockCodes: transactions.reservedBlockCodes,
      reservationAllocatedAt: transactions.reservationAllocatedAt,
      reservationNote: transactions.reservationNote,
      preferredCleaningDate: transactions.preferredCleaningDate,
      stripeCheckoutSessionId: transactions.stripeCheckoutSessionId,
      createdAt: transactions.createdAt,
      sponsorName: sponsors.name,
      sponsorBusiness: sponsors.businessName,
      sponsorEmail: sponsors.email,
    })
    .from(transactions)
    .leftJoin(sponsors, eq(transactions.sponsorId, sponsors.id))
    .where(isNotNull(transactions.reservedBlockCount))
    .orderBy(desc(transactions.createdAt))

  const open = rows.filter((r) => !r.reservationAllocatedAt)
  return NextResponse.json({
    data: rows,
    summary: {
      open: open.length,
      openBlocks: open.reduce((n, r) => n + (r.reservedBlockCount ?? 0), 0),
      openCents: open.reduce((n, r) => n + Number(r.amount || 0), 0),
      allocated: rows.length - open.length,
    },
  })
}

const actionSchema = z.object({
  id: z.number().int().positive(),
  action: z.enum(['allocate', 'reopen']),
  note: z.string().trim().max(1000).optional(),
})

export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const parsed = actionSchema.safeParse(await request.json().catch(() => null))
  if (!parsed.success) {
    return NextResponse.json({ error: 'Invalid request' }, { status: 400 })
  }
  const { id, action, note } = parsed.data

  const [row] = await db
    .select({ id: transactions.id, reservedBlockCount: transactions.reservedBlockCount })
    .from(transactions)
    .where(eq(transactions.id, id))
    .limit(1)
  if (!row || row.reservedBlockCount === null) {
    return NextResponse.json({ error: 'Not a block reservation' }, { status: 404 })
  }

  const now = new Date().toISOString()
  await db
    .update(transactions)
    .set(
      action === 'allocate'
        ? { reservationAllocatedAt: now, reservationNote: note || null, updatedAt: now }
        : { reservationAllocatedAt: null, updatedAt: now },
    )
    .where(eq(transactions.id, id))

  return NextResponse.json({ success: true })
}
