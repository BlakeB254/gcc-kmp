# Minimal scheduler image for Render Cron Jobs.
#
# Deliberately contains no application code. Its only job is to authenticate to
# an existing Next.js cron endpoint and invoke it on a schedule, so all queue
# logic stays in `site/` with Drizzle as the single source of truth. Render's
# `rootDir: ops/render-cron` keeps the build scoped to this directory rather
# than the Kotlin/Next/Xcode monorepo above it.
FROM alpine:3.20

RUN apk add --no-cache curl ca-certificates

COPY tick.sh /usr/local/bin/tick.sh
RUN chmod +x /usr/local/bin/tick.sh

ENTRYPOINT ["/usr/local/bin/tick.sh"]
