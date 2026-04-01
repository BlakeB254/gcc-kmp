'use client'

import * as React from 'react'
import Link from 'next/link'
import { MapPin, Camera, Send, CheckCircle, AlertCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

export default function NominatePage() {
  const [isSubmitted, setIsSubmitted] = React.useState(false)
  const [isSubmitting, setIsSubmitting] = React.useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSubmitting(true)
    // Simulate API call
    await new Promise((resolve) => setTimeout(resolve, 1500))
    setIsSubmitted(true)
    setIsSubmitting(false)
  }

  if (isSubmitted) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
        <div className="section-container flex min-h-[60vh] items-center justify-center py-16">
          <Card className="max-w-md text-center">
            <CardContent className="pt-8">
              <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
                <CheckCircle className="h-8 w-8 text-brand-green-600" />
              </div>
              <h1 className="mb-2 text-2xl font-bold">Nomination Submitted!</h1>
              <p className="mb-6 text-muted-foreground">
                Thank you for helping us identify blocks that need cleaning.
                We'll review your nomination and let you know when it's approved.
              </p>
              <div className="flex flex-col gap-3">
                <Button asChild>
                  <Link href="/impact">View Community Impact</Link>
                </Button>
                <Button variant="ghost" onClick={() => setIsSubmitted(false)}>
                  Nominate Another Block
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Header */}
      <section className="section-container py-12 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
          <MapPin className="h-8 w-8 text-brand-green-600" />
        </div>
        <h1 className="mb-2 text-3xl font-bold">Nominate a Block</h1>
        <p className="mx-auto max-w-xl text-muted-foreground">
          Know a block that needs cleaning? Nominate it and help us prioritize
          areas with the greatest need. Community nominations help guide our
          work.
        </p>
      </section>

      {/* Form */}
      <section className="section-container pb-16">
        <Card className="mx-auto max-w-2xl">
          <CardHeader>
            <CardTitle>Block Nomination Form</CardTitle>
            <CardDescription>
              Fill out the details below. All nominations are reviewed by our team.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-6">
              {/* Contact Info */}
              <div className="space-y-4">
                <h3 className="font-medium">Your Information</h3>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-2">
                    <Label htmlFor="name">Your Name</Label>
                    <Input id="name" placeholder="John Doe" required />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="email">Email</Label>
                    <Input id="email" type="email" placeholder="john@example.com" required />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="phone">Phone (Optional)</Label>
                  <Input id="phone" type="tel" placeholder="(312) 555-0123" />
                </div>
              </div>

              {/* Block Info */}
              <div className="space-y-4">
                <h3 className="font-medium">Block Location</h3>
                <div className="space-y-2">
                  <Label htmlFor="address">Street Address or Cross Streets</Label>
                  <Input
                    id="address"
                    placeholder="e.g., 3300 W Roosevelt Rd or Roosevelt & Kedzie"
                    required
                  />
                  <p className="text-xs text-muted-foreground">
                    Be as specific as possible so we can locate the exact block
                  </p>
                </div>
              </div>

              {/* Reason & Urgency */}
              <div className="space-y-4">
                <h3 className="font-medium">Details</h3>
                <div className="space-y-2">
                  <Label htmlFor="urgency">Urgency Level</Label>
                  <Select defaultValue="normal">
                    <SelectTrigger id="urgency">
                      <SelectValue placeholder="Select urgency" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="low">Low - General maintenance needed</SelectItem>
                      <SelectItem value="normal">Normal - Noticeable litter/debris</SelectItem>
                      <SelectItem value="high">High - Significant problem</SelectItem>
                      <SelectItem value="urgent">Urgent - Safety concern</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="reason">Why does this block need cleaning?</Label>
                  <Textarea
                    id="reason"
                    placeholder="Describe the current condition and why you think this block should be prioritized..."
                    rows={4}
                    required
                  />
                </div>
              </div>

              {/* Photos */}
              <div className="space-y-4">
                <h3 className="font-medium">Photos (Optional)</h3>
                <div className="rounded-lg border-2 border-dashed p-8 text-center">
                  <Camera className="mx-auto mb-2 h-8 w-8 text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">
                    Drag and drop photos or click to upload
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    Max 4 photos, JPG or PNG, up to 5MB each
                  </p>
                  <Input
                    type="file"
                    accept="image/*"
                    multiple
                    className="mt-4"
                  />
                </div>
              </div>

              {/* Info box */}
              <div className="flex gap-3 rounded-lg bg-brand-blue-50 p-4">
                <AlertCircle className="h-5 w-5 flex-shrink-0 text-brand-blue-600" />
                <div className="text-sm">
                  <p className="font-medium text-brand-blue-800">What happens next?</p>
                  <p className="text-brand-blue-700">
                    Our team will review your nomination within 48 hours. If approved,
                    the block will be added to our map for workers to claim and sponsors
                    to fund.
                  </p>
                </div>
              </div>

              {/* Submit */}
              <Button type="submit" size="lg" className="w-full" disabled={isSubmitting}>
                {isSubmitting ? (
                  <>Submitting...</>
                ) : (
                  <>
                    <Send className="mr-2 h-5 w-5" />
                    Submit Nomination
                  </>
                )}
              </Button>
            </form>
          </CardContent>
        </Card>
      </section>
    </div>
  )
}
