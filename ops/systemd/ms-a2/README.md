# ms-a2 cron timers

User systemd units on ms-a2 that `curl` the `CRON_SECRET`-guarded `/api/cron/*`
routes. These are what actually schedule GCC's cron work today: each run lands
in `journalctl --user -u gcc-cron-<name>`. Render (`render.yaml`) describes the
same idea but there is no evidence its jobs were ever created — see
`docs/governance/cron-and-scheduling.md`.

The units hold no secret. `CRON_SECRET` comes from
`~/.config/cdx/gcc-cron.env` (mode 600, not in git), the same value as on the
Vercel project.

| Unit | When (ms-a2 local time unless marked) | Route |
|---|---|---|
| `gcc-cron-route-releases` | daily 06:00 | `/api/cron/route-releases` |
| `gcc-cron-building-footprints` | hourly at :23 | `/api/cron/building-footprints` |
| `gcc-cron-verification-docs` | daily 04:37 UTC | `/api/cron/verification-docs?retention_days=90&retention=dry` |
| `gcc-cron-scan-index` | every 30 min at :11 / :41 | `/api/cron/scan-index` |
| `gcc-cron-civic-sync` | every 6 h at :47 | `/api/cron/civic-sync` |
| `gcc-cron-scan-watchdog` | every 6 h at :53 (from 03:53) | `/api/cron/scan-watchdog` |

The first three are copies of the units already installed on ms-a2, versioned
here so they can be reviewed and restored. The last three are new.

## Install (owner / integrator)

```bash
cp ops/systemd/ms-a2/gcc-cron-{scan-index,civic-sync,scan-watchdog}.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user start gcc-cron-scan-index.service && journalctl --user -u gcc-cron-scan-index -n 5
systemctl --user enable --now gcc-cron-scan-index.timer gcc-cron-civic-sync.timer gcc-cron-scan-watchdog.timer
```

Deploy the routes to Vercel before enabling the timers, or every tick 404s.
