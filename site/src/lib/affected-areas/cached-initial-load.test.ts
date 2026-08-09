import assert from 'node:assert/strict'
import test from 'node:test'
import 'next/dist/server/node-environment-baseline'
import {
  CacheHandler,
  IncrementalCache,
  type CacheHandlerValue,
} from 'next/dist/server/lib/incremental-cache'

import {
  affectedAreaCacheKey,
  createInitialAffectedAreasLoader,
  type AffectedAreasGeoJson,
} from './cached-initial-load'

class MemoryCacheHandler extends CacheHandler {
  static entries = new Map<string, CacheHandlerValue>()

  override async get(cacheKey: string): Promise<CacheHandlerValue | null> {
    return MemoryCacheHandler.entries.get(cacheKey) ?? null
  }

  override async set(cacheKey: string, value: CacheHandlerValue['value']): Promise<void> {
    MemoryCacheHandler.entries.set(cacheKey, { lastModified: Date.now(), value })
  }
}

function createNextIncrementalCache(): IncrementalCache {
  MemoryCacheHandler.entries.clear()

  return new IncrementalCache({
    dev: false,
    requestHeaders: {},
    maxMemoryCacheSize: 1_024 * 1_024,
    getPrerenderManifest: () => ({
      version: 4,
      routes: {},
      dynamicRoutes: {},
      notFoundRoutes: [],
      preview: {
        previewModeId: 'test-preview-mode',
        previewModeEncryptionKey: 'test-preview-encryption-key',
        previewModeSigningKey: 'test-preview-mode-signing-key',
      },
    }),
    CurCacheHandler: MemoryCacheHandler,
  })
}

function collection(generatedAt: string): AffectedAreasGeoJson {
  return {
    type: 'FeatureCollection',
    features: [],
    generatedAt,
    count: 0,
  }
}

test('affected-area cache identities keep every service-area scope isolated', () => {
  // Catches dropping serviceAreaId from the server-side cache key, which
  // would leak one service area's full-map snapshot into another area.
  assert.deepEqual(
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 0 }),
    ['affected-areas', 'full-snapshot', 'service-area:"12"', 'min-status:0'],
  )
  assert.notDeepEqual(
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: '13', minStatus: 0 }),
  )
  assert.notDeepEqual(
    affectedAreaCacheKey({ serviceAreaId: null, minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 0 }),
  )
  assert.notDeepEqual(
    affectedAreaCacheKey({ serviceAreaId: null, minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: 'all', minStatus: 0 }),
  )
  assert.deepEqual(
    affectedAreaCacheKey({ serviceAreaId: '01', minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: '1-anything', minStatus: 0 }),
  )
  assert.deepEqual(
    affectedAreaCacheKey({ serviceAreaId: null, minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: '', minStatus: 0 }),
  )
})

test('affected-area cache identities keep every minimum-status scope isolated', () => {
  // Catches dropping the normalized status threshold from the key, which
  // would let an available-block response satisfy a completed-only request.
  assert.notDeepEqual(
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 0 }),
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 3 }),
  )
  assert.deepEqual(
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: null }),
    affectedAreaCacheKey({ serviceAreaId: '12', minStatus: 0 }),
  )
})

test('initial affected-area loads reuse values only inside the complete scope', async () => {
  // Catches a production cache adapter that computes a complete key but then
  // fails to give that key to Next's incremental cache.
  const cacheGlobal = globalThis as typeof globalThis & { __incrementalCache?: IncrementalCache }
  const originalCache = cacheGlobal.__incrementalCache
  cacheGlobal.__incrementalCache = createNextIncrementalCache()
  let runs = 0
  const load = createInitialAffectedAreasLoader(async (scope) => {
    runs += 1
    return collection(`${scope.serviceAreaId ?? 'all'}:${scope.minStatus ?? 'all'}:${runs}`)
  })

  try {
    const area12Available = await load({ serviceAreaId: '12', minStatus: 0 })
    const area12AvailableAgain = await load({ serviceAreaId: '12', minStatus: 0 })
    const area13Available = await load({ serviceAreaId: '13', minStatus: 0 })
    const area12Completed = await load({ serviceAreaId: '12', minStatus: 3 })

    assert.equal(area12Available.generatedAt, '12:0:1')
    assert.equal(area12AvailableAgain.generatedAt, '12:0:1')
    assert.equal(area13Available.generatedAt, '13:0:2')
    assert.equal(area12Completed.generatedAt, '12:3:3')
  } finally {
    if (originalCache) {
      cacheGlobal.__incrementalCache = originalCache
    } else {
      delete cacheGlobal.__incrementalCache
    }
  }
})

test('failed affected-area loads are retried instead of cached', async () => {
  // Catches converting a rejected load into a cached value/error sentinel;
  // the next request must be allowed to recover immediately.
  const cacheGlobal = globalThis as typeof globalThis & { __incrementalCache?: IncrementalCache }
  const originalCache = cacheGlobal.__incrementalCache
  cacheGlobal.__incrementalCache = createNextIncrementalCache()
  let runs = 0
  const load = createInitialAffectedAreasLoader(async () => {
    runs += 1
    if (runs === 1) throw new Error('transient database failure')
    return collection('recovered')
  })

  try {
    await assert.rejects(
      load({ serviceAreaId: null, minStatus: 0 }),
      /transient database failure/,
    )
    const recovered = await load({ serviceAreaId: null, minStatus: 0 })

    assert.equal(recovered.generatedAt, 'recovered')
    assert.equal(runs, 2)
  } finally {
    if (originalCache) {
      cacheGlobal.__incrementalCache = originalCache
    } else {
      delete cacheGlobal.__incrementalCache
    }
  }
})
