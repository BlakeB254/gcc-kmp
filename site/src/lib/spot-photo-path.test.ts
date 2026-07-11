import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spotPhotoPathname } from './spot-photo-path'

test('namespaces photos under the worker', () => {
  assert.ok(spotPhotoPathname(42, 'photo_0').startsWith('spots/42/'))
})

test('ends in .jpg', () => {
  assert.ok(spotPhotoPathname(42, 'photo_0').endsWith('.jpg'))
})

test('sanitises a hostile form key', () => {
  const path = spotPhotoPathname(42, '../../etc/passwd')
  assert.equal(path.includes('..'), false)
  assert.equal(path.split('/').length, 3, 'must not escape spots/<worker>/')
})

test('an empty form key still yields a valid path', () => {
  const path = spotPhotoPathname(42, '')
  assert.ok(path.startsWith('spots/42/'))
  assert.ok(path.endsWith('.jpg'))
})
