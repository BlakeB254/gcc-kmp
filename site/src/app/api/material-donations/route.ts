import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { materialDonations } from '@/db/schema'
import { sendEmail } from '@/lib/email'

export const runtime = 'nodejs'

interface MaterialDonationRequest {
  name: string
  email: string
  phone?: string
  company?: string
  items: string
  itemCategories?: string[]
  quantity?: string
  condition?: string
  preferredMethod?: 'pickup' | 'dropoff'
  address?: string
  availableDates?: string
  notes?: string
}

/**
 * POST /api/material-donations
 * Submit a material donation inquiry
 */
export async function POST(request: NextRequest) {
  try {
    const body: MaterialDonationRequest = await request.json()

    // Validation
    if (!body.name || !body.email || !body.items) {
      return NextResponse.json(
        { error: 'Name, email, and items description are required' },
        { status: 400 }
      )
    }

    // Create the inquiry record
    const [donation] = await db
      .insert(materialDonations)
      .values({
        name: body.name,
        email: body.email,
        phone: body.phone || null,
        company: body.company || null,
        items: body.items,
        itemCategories: body.itemCategories || null,
        quantity: body.quantity || null,
        condition: body.condition || null,
        preferredMethod: body.preferredMethod || null,
        address: body.address || null,
        availableDates: body.availableDates || null,
        notes: body.notes || null,
        status: 'pending',
      })
      .returning({ id: materialDonations.id })

    // Send confirmation email to donor
    const emailContent = materialDonationConfirmationEmail(body)
    await sendEmail({
      to: body.email,
      subject: 'Thank You for Your Material Donation Inquiry - GCC',
      html: emailContent.html,
      text: emailContent.text,
    })

    // Send notification to admin
    const adminEmail = process.env.ADMIN_EMAIL || 'donate@globalcommunitycleaners.org'
    await sendEmail({
      to: adminEmail,
      subject: `New Material Donation Inquiry from ${body.name}`,
      html: materialDonationAdminNotification(body, donation.id),
      text: `New material donation inquiry from ${body.name} (${body.email}). Items: ${body.items}`,
    })

    return NextResponse.json({
      success: true,
      id: donation.id,
      message: 'Thank you! We will contact you soon to arrange your donation.',
    })
  } catch (error) {
    console.error('Material donation submission error:', error)
    return NextResponse.json(
      { error: 'Failed to submit donation inquiry' },
      { status: 500 }
    )
  }
}

function materialDonationConfirmationEmail(data: MaterialDonationRequest): { html: string; text: string } {
  const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'

  const html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Thank You for Your Material Donation Inquiry</title>
</head>
<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f5f5f5;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 20px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width: 600px; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);">
          <!-- Header -->
          <tr>
            <td style="background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); padding: 32px; text-align: center;">
              <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700;">Thank You!</h1>
              <p style="margin: 8px 0 0; color: #ffffff; font-size: 14px; font-weight: 500;">Your material donation inquiry has been received</p>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding: 40px 32px;">
              <h2 style="margin: 0 0 16px; color: #1a1a1a; font-size: 24px;">Hi ${data.name}!</h2>

              <p style="margin: 0 0 24px; color: #4a4a4a; font-size: 16px; line-height: 1.6;">
                Thank you for your interest in donating supplies and equipment to Global Community Cleaners! Your generosity helps keep our workers equipped and our streets clean.
              </p>

              <!-- Donation Details -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color: #fef3c7; border-radius: 12px; margin-bottom: 24px;">
                <tr>
                  <td style="padding: 24px;">
                    <p style="margin: 0 0 16px; color: #92400e; font-size: 14px; font-weight: 600; text-transform: uppercase;">Your Inquiry Details</p>
                    <p style="margin: 0 0 8px; color: #1a1a1a; font-size: 14px;"><strong>Items:</strong> ${data.items}</p>
                    ${data.quantity ? `<p style="margin: 0 0 8px; color: #1a1a1a; font-size: 14px;"><strong>Quantity:</strong> ${data.quantity}</p>` : ''}
                    ${data.preferredMethod ? `<p style="margin: 0; color: #1a1a1a; font-size: 14px;"><strong>Preferred Method:</strong> ${data.preferredMethod === 'pickup' ? 'Pickup' : 'Drop-off'}</p>` : ''}
                  </td>
                </tr>
              </table>

              <!-- Next Steps -->
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                <tr>
                  <td style="padding: 16px; background-color: #ecfdf5; border-radius: 12px; border-left: 4px solid #10b981;">
                    <p style="margin: 0 0 12px; color: #065f46; font-size: 14px; font-weight: 600;">WHAT HAPPENS NEXT:</p>
                    <ol style="margin: 0; padding-left: 20px; color: #4a4a4a; font-size: 14px; line-height: 1.8;">
                      <li>Our team will review your donation inquiry</li>
                      <li>We'll contact you within 2-3 business days</li>
                      <li>We'll arrange pickup or provide drop-off details</li>
                      <li>You'll receive a receipt for tax purposes</li>
                    </ol>
                  </td>
                </tr>
              </table>

              <p style="margin: 0; color: #4a4a4a; font-size: 14px; line-height: 1.6;">
                If you have any questions, feel free to reply to this email or contact us at <a href="mailto:donate@globalcommunitycleaners.org" style="color: #f59e0b;">donate@globalcommunitycleaners.org</a>
              </p>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color: #f9f9f9; padding: 24px 32px; text-align: center; border-top: 1px solid #e5e5e5;">
              <p style="margin: 0 0 8px; color: #6b6b6b; font-size: 12px;">
                Global Community Cleaners | Chicago, IL
              </p>
              <p style="margin: 0; color: #9a9a9a; font-size: 12px;">
                Thank you for supporting clean streets and local jobs.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
`

  const text = `
Hi ${data.name}!

Thank you for your interest in donating supplies and equipment to Global Community Cleaners!

YOUR INQUIRY DETAILS
--------------------
Items: ${data.items}
${data.quantity ? `Quantity: ${data.quantity}` : ''}
${data.preferredMethod ? `Preferred Method: ${data.preferredMethod === 'pickup' ? 'Pickup' : 'Drop-off'}` : ''}

WHAT HAPPENS NEXT:
1. Our team will review your donation inquiry
2. We'll contact you within 2-3 business days
3. We'll arrange pickup or provide drop-off details
4. You'll receive a receipt for tax purposes

If you have any questions, contact us at donate@globalcommunitycleaners.org

---
Global Community Cleaners
Clean Streets. Real Money. Your Neighborhood.
`

  return { html, text }
}

function materialDonationAdminNotification(data: MaterialDonationRequest, id: number): string {
  return `
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 20px;">
  <h2 style="color: #f59e0b;">New Material Donation Inquiry #${id}</h2>

  <h3>Donor Information</h3>
  <ul>
    <li><strong>Name:</strong> ${data.name}</li>
    <li><strong>Email:</strong> ${data.email}</li>
    ${data.phone ? `<li><strong>Phone:</strong> ${data.phone}</li>` : ''}
    ${data.company ? `<li><strong>Company:</strong> ${data.company}</li>` : ''}
  </ul>

  <h3>Donation Details</h3>
  <ul>
    <li><strong>Items:</strong> ${data.items}</li>
    ${data.quantity ? `<li><strong>Quantity:</strong> ${data.quantity}</li>` : ''}
    ${data.condition ? `<li><strong>Condition:</strong> ${data.condition}</li>` : ''}
    ${data.preferredMethod ? `<li><strong>Preferred Method:</strong> ${data.preferredMethod}</li>` : ''}
    ${data.address ? `<li><strong>Address:</strong> ${data.address}</li>` : ''}
    ${data.availableDates ? `<li><strong>Available Dates:</strong> ${data.availableDates}</li>` : ''}
    ${data.notes ? `<li><strong>Notes:</strong> ${data.notes}</li>` : ''}
  </ul>

  <p style="margin-top: 24px;">
    <a href="mailto:${data.email}?subject=Your%20GCC%20Material%20Donation%20Inquiry"
       style="background-color: #f59e0b; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px;">
      Reply to Donor
    </a>
  </p>
</body>
</html>
`
}
