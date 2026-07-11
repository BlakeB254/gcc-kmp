import assert from 'node:assert/strict'
import { test } from 'node:test'
import { format } from 'node:util'

import Image from './opengraph-image'

test('worker signup OG image renders without dynamic font fetch failures', async (t) => {
  const originalError = console.error
  const errorMessages: string[] = []

  console.error = (...args: unknown[]) => {
    errorMessages.push(format(...args))
  }

  t.after(() => {
    console.error = originalError
  })

  const response = await Image()
  const imageBytes = Buffer.from(await response.arrayBuffer())

  assert.ok(imageBytes.byteLength > 0)
  assert.equal(
    errorMessages.some((message) => message.includes('Failed to load dynamic font')),
    false,
    `unexpected OG font fetch failure:\n${errorMessages.join('\n')}`
  )
})
