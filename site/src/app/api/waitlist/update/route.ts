import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { waitlistEntries } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { z } from 'zod'

export const runtime = 'nodejs'

/**
 * Waitlist Update API
 *
 * Allows users to update their profile information (name, location, preferences).
 * IMPORTANT: Rankings are NEVER updated - they are immutable and represent
 * the user's position at the time of original signup.
 *
 * Location updates are useful for:
 * - Users who move to a new city
 * - Correcting typos in their original submission
 * - Adding neighborhood information
 */

const updateSchema = z.object({
  id: z.coerce.number().min(1, 'Profile ID is required'),
  email: z.string().email('Please verify with your email'),
  updates: z.object({
    name: z.string().optional(),
    location: z
      .object({
        country: z.string().optional(),
        countryCode: z.string().optional(),
        state: z.string().optional(),
        stateCode: z.string().optional(),
        city: z.string().optional(),
        neighborhood: z.string().optional(),
      })
      .optional(),
    preferences: z
      .object({
        wantsToBeWorker: z.boolean().optional(),
        wantsToBeSponsor: z.boolean().optional(),
        wantsToVolunteer: z.boolean().optional(),
        emailUpdates: z.boolean().optional(),
      })
      .optional(),
  }),
})

export async function PATCH(request: NextRequest) {
  try {
    const body = await request.json()

    // Validate input
    const result = updateSchema.safeParse(body)
    if (!result.success) {
      return NextResponse.json(
        {
          success: false,
          error: result.error.issues[0]?.message || 'Invalid input',
        },
        { status: 400 }
      )
    }

    const { id, email, updates } = result.data

    // Fetch existing entry
    const [entry] = await db
      .select()
      .from(waitlistEntries)
      .where(eq(waitlistEntries.id, id))
      .limit(1)

    if (!entry) {
      return NextResponse.json(
        { success: false, error: 'Profile not found' },
        { status: 404 }
      )
    }

    // Verify email matches (simple auth)
    if (entry.email !== email) {
      return NextResponse.json(
        { success: false, error: 'Email does not match this profile' },
        { status: 403 }
      )
    }

    // Build update data - NEVER include rankings (they're immutable)
    const updateData: Partial<typeof waitlistEntries.$inferInsert> = {
      updatedAt: new Date().toISOString(),
    }

    if (updates.name !== undefined) {
      updateData.name = updates.name
    }

    if (updates.location) {
      if (updates.location.country !== undefined) {
        updateData.locationCountry = updates.location.country
      }
      if (updates.location.countryCode !== undefined) {
        updateData.locationCountryCode = updates.location.countryCode
      }
      if (updates.location.state !== undefined) {
        updateData.locationState = updates.location.state
      }
      if (updates.location.stateCode !== undefined) {
        updateData.locationStateCode = updates.location.stateCode
      }
      if (updates.location.city !== undefined) {
        updateData.locationCity = updates.location.city
      }
      if (updates.location.neighborhood !== undefined) {
        updateData.locationNeighborhood = updates.location.neighborhood
      }
    }

    if (updates.preferences) {
      if (updates.preferences.wantsToBeWorker !== undefined) {
        updateData.preferencesWantsToBeWorker = updates.preferences.wantsToBeWorker
      }
      if (updates.preferences.wantsToBeSponsor !== undefined) {
        updateData.preferencesWantsToBeSponsor = updates.preferences.wantsToBeSponsor
      }
      if (updates.preferences.wantsToVolunteer !== undefined) {
        updateData.preferencesWantsToVolunteer = updates.preferences.wantsToVolunteer
      }
      if (updates.preferences.emailUpdates !== undefined) {
        updateData.preferencesEmailUpdates = updates.preferences.emailUpdates
      }
    }

    // Perform update
    const [updatedEntry] = await db
      .update(waitlistEntries)
      .set(updateData)
      .where(eq(waitlistEntries.id, id))
      .returning()

    return NextResponse.json({
      success: true,
      message: 'Profile updated successfully',
      profile: {
        id: updatedEntry.id,
        name: updatedEntry.name,
        location: {
          country: updatedEntry.locationCountry,
          countryCode: updatedEntry.locationCountryCode,
          state: updatedEntry.locationState,
          stateCode: updatedEntry.locationStateCode,
          city: updatedEntry.locationCity,
          neighborhood: updatedEntry.locationNeighborhood,
        },
        rankings: {
          global: Number(updatedEntry.rankingsGlobal),
          country: Number(updatedEntry.rankingsCountry),
          state: Number(updatedEntry.rankingsState),
          city: Number(updatedEntry.rankingsCity),
          neighborhood: updatedEntry.rankingsNeighborhood
            ? Number(updatedEntry.rankingsNeighborhood)
            : null,
        },
        earlyAdopterTier: updatedEntry.earlyAdopterTier,
        referralCode: updatedEntry.referralCode,
      },
    })
  } catch (error) {
    console.error('Waitlist update error:', error)
    return NextResponse.json(
      { success: false, error: 'Failed to update profile' },
      { status: 500 }
    )
  }
}
