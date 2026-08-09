export type PublicCachePolicy = {
  browserMaxAgeSeconds: number;
  sharedMaxAgeSeconds: number;
  staleWhileRevalidateSeconds: number;
};

export const PRIVATE_NO_STORE = 'private, no-store';

function requireNonNegativeInteger(name: string, seconds: number): void {
  if (!Number.isInteger(seconds) || seconds < 0) {
    throw new RangeError(`${name} must be a non-negative integer`);
  }
}

export function publicCacheControl(policy: PublicCachePolicy): string {
  requireNonNegativeInteger('browserMaxAgeSeconds', policy.browserMaxAgeSeconds);
  requireNonNegativeInteger('sharedMaxAgeSeconds', policy.sharedMaxAgeSeconds);
  requireNonNegativeInteger('staleWhileRevalidateSeconds', policy.staleWhileRevalidateSeconds);

  return `public, max-age=${policy.browserMaxAgeSeconds}, s-maxage=${policy.sharedMaxAgeSeconds}, stale-while-revalidate=${policy.staleWhileRevalidateSeconds}`;
}
