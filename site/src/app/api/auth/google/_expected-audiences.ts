export function googleAuthExpectedAudiences(
  env: Record<string, string | undefined>
): string[] {
  return [env.GOOGLE_IOS_CLIENT_ID, env.GOOGLE_WEB_CLIENT_ID].flatMap((value) => {
    if (typeof value !== 'string') return []
    const normalized = value.trim()
    return normalized.length > 0 ? [normalized] : []
  })
}
