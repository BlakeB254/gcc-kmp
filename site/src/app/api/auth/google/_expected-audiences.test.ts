import { test } from 'node:test'
import assert from 'node:assert/strict'

import { googleAuthExpectedAudiences } from './_expected-audiences'

test('googleAuthExpectedAudiences trims surrounding whitespace so a valid aud still matches', () => {
  const tokenAudience =
    '674514823110-mebr2cehucmac7u4dorkeh4alm2hs80g.apps.googleusercontent.com'

  const expected = googleAuthExpectedAudiences({
    GOOGLE_IOS_CLIENT_ID: `${tokenAudience}\n`,
    GOOGLE_WEB_CLIENT_ID:
      ' 674514823110-g3q68m8hbatmibtr8srbqhm3v11bd01d.apps.googleusercontent.com ',
  })

  assert.deepEqual(expected, [
    tokenAudience,
    '674514823110-g3q68m8hbatmibtr8srbqhm3v11bd01d.apps.googleusercontent.com',
  ])
  assert.equal(expected.includes(tokenAudience), true)
})

test('googleAuthExpectedAudiences drops unset and blank values after trimming', () => {
  const expected = googleAuthExpectedAudiences({
    GOOGLE_IOS_CLIENT_ID: ' \n ',
    GOOGLE_WEB_CLIENT_ID: undefined,
  })

  assert.deepEqual(expected, [])
})
