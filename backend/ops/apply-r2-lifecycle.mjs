// Apply SAFE, non-destructive lifecycle rules to the R2 scan bucket.
//
// WHY these rules (and not a "delete raw/" rule): reconstruction is still
// MANUAL, so a time-based deletion of raw/ would delete scans that were never
// processed. Until reconstruction is automated (Phase 3), we only:
//   1. abort incomplete multipart uploads after 7 days   (pure hygiene, safe)
//   2. transition raw/ to Infrequent Access after 30 days (cheaper storage, no data loss)
//
// The real "delete raw/ N days AFTER a reconstruction exists" rule lands in
// Phase 3, driven by the indexer (scan_sessions.reconstruction_version), not by
// a blind clock.
//
// RUN IT (from the repo's site/ dir, which already has @aws-sdk/client-s3):
//   R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... R2_BUCKET_NAME=gcc-scan-storage \
//     node ../backend/ops/apply-r2-lifecycle.mjs
// Add --dry-run to print the config without applying it.

import {
  S3Client,
  PutBucketLifecycleConfigurationCommand,
  GetBucketLifecycleConfigurationCommand,
} from '@aws-sdk/client-s3'

const { R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME } = process.env
const dryRun = process.argv.includes('--dry-run')

for (const [k, v] of Object.entries({ R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET_NAME })) {
  if (!v) { console.error(`Missing env var: ${k}`); process.exit(1) }
}

const rules = [
  {
    ID: 'abort-incomplete-multipart-7d',
    Status: 'Enabled',
    Filter: { Prefix: '' },
    AbortIncompleteMultipartUpload: { DaysAfterInitiation: 7 },
  },
  {
    ID: 'raw-to-infrequent-access-30d',
    Status: 'Enabled',
    Filter: { Prefix: 'raw/' },
    Transitions: [{ Days: 30, StorageClass: 'STANDARD_IA' }],
  },
]

const config = { Rules: rules }
console.log(`Bucket: ${R2_BUCKET_NAME}`)
console.log(JSON.stringify(config, null, 2))
if (dryRun) { console.log('\n--dry-run: not applied.'); process.exit(0) }

const client = new S3Client({
  region: 'auto',
  endpoint: `https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com`,
  credentials: { accessKeyId: R2_ACCESS_KEY_ID, secretAccessKey: R2_SECRET_ACCESS_KEY },
})

try {
  await client.send(new PutBucketLifecycleConfigurationCommand({
    Bucket: R2_BUCKET_NAME,
    LifecycleConfiguration: config,
  }))
  const current = await client.send(new GetBucketLifecycleConfigurationCommand({ Bucket: R2_BUCKET_NAME }))
  console.log('\nApplied. Active rules:')
  console.log(JSON.stringify(current.Rules, null, 2))
} catch (err) {
  // If R2 rejects the IA transition in this account, fall back to the always-safe
  // abort rule alone so the run still leaves the bucket in a good state.
  if (String(err?.name || err).includes('InvalidStorageClass') || String(err).includes('Transition')) {
    console.warn('\nIA transition rejected by R2; applying the abort-multipart rule only.')
    await client.send(new PutBucketLifecycleConfigurationCommand({
      Bucket: R2_BUCKET_NAME,
      LifecycleConfiguration: { Rules: [rules[0]] },
    }))
    console.log('Applied abort-incomplete-multipart-7d only.')
  } else {
    console.error('Failed to apply lifecycle config:', err)
    process.exit(1)
  }
}
