// First Newsletter — May 1, 2026 — "Sixty Years and Two Blocks"
//
// Email-safe HTML: table-based layout, inline styles, 600px max width.
// Tested visually against Gmail (web/mobile), Apple Mail (macOS/iOS), Outlook web.
//
// Editorial positioning: GCC = historical legitimacy + academic data +
// civic-cleanliness data expertise. We don't pitch like a charity; we present
// like the field-evidence layer for community cleanliness in our neighborhoods.

interface Issue1Data {
  recipientName?: string | null
  unsubscribeUrl: string  // Required for CAN-SPAM compliance
  appUrl: string          // e.g. https://globalcommunitycleaners.org
  logoUrl: string         // Absolute URL to logo (must be public, not file://)
  mediaBaseUrl?: string   // Optional override for stat / condition image base. Defaults to `${appUrl}/newsletter/issue-01`.
}

/**
 * Picks a friendly first-name greeting. Falls back to "friend" if the name
 * looks like an organization, contains weird characters, or is empty —
 * "Hi The" or "Hi Orvil1955!" feels worse than "Hi friend".
 */
function friendlyGreetingName(raw: string | null | undefined): string {
  if (!raw) return 'friend'
  const trimmed = raw.trim()
  if (!trimmed) return 'friend'
  const first = trimmed.split(/\s+/)[0]
  // Reject if too short, contains digits/special chars, or is a determiner
  if (first.length < 2) return 'friend'
  if (!/^[A-Za-z'\-]+$/.test(first)) return 'friend'
  if (/^(the|a|an|global|community|gcc|test|admin)$/i.test(first)) return 'friend'
  // Title-case it (handles "blake" → "Blake", "BLAKE" → "Blake")
  return first.charAt(0).toUpperCase() + first.slice(1).toLowerCase()
}

const BRAND_GREEN = '#16763a'
const BRAND_GREEN_DARK = '#0b3b1d'
const ACCENT_GOLD = '#fbbf24'
const NEUTRAL_900 = '#171717'
const NEUTRAL_700 = '#404040'
const NEUTRAL_500 = '#737373'
const NEUTRAL_300 = '#d4d4d4'
const NEUTRAL_200 = '#e5e5e5'
const BG_GRAY = '#f5f5f5'
const BG_EMERALD = '#ecfdf5'

export function newsletterIssue1Email(d: Issue1Data): { html: string; text: string; subject: string } {
  const subject = "Sixty years and two blocks — GCC Issue #1"
  const greeting = friendlyGreetingName(d.recipientName)
  const media = d.mediaBaseUrl || `${d.appUrl}/newsletter/issue-01`

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<title>${subject}</title>
<!--[if mso]>
<style type="text/css">table, td { border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; } body, table, td, p, a { font-family: Arial, sans-serif !important; }</style>
<![endif]-->
</head>
<body style="margin:0;padding:0;background-color:${BG_GRAY};font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:${NEUTRAL_900};-webkit-text-size-adjust:100%;">

<!-- Preheader -->
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:${BG_GRAY};">
Sixty years ago Dr. King moved to North Lawndale. Today, six blocks away, our work begins. 110+ hotspots already mapped. — ${greeting}, here is Issue #1.
</div>

<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:${BG_GRAY};">
<tr><td align="center" style="padding:24px 12px;">

<table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="max-width:600px;width:100%;background-color:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 4px rgba(0,0,0,0.04);">

  <!-- Header -->
  <tr>
    <td style="padding:32px 32px 16px 32px;background:linear-gradient(135deg,${BRAND_GREEN} 0%,${BRAND_GREEN_DARK} 100%);text-align:center;">
      <img src="${d.logoUrl}" alt="Global Community Cleaners NFP" width="240" style="display:block;margin:0 auto;width:240px;max-width:65%;height:auto;background:transparent;" />
      <p style="margin:18px 0 0 0;color:#ffffff;font-size:11px;font-weight:600;letter-spacing:0.18em;text-transform:uppercase;opacity:0.85;">Issue #1 · May 1, 2026 · The Founder's Letter</p>
    </td>
  </tr>

  <!-- Lead -->
  <tr>
    <td style="padding:36px 32px 8px 32px;">
      <h1 style="margin:0 0 16px 0;color:${NEUTRAL_900};font-size:32px;line-height:1.1;font-weight:800;letter-spacing:-0.018em;">Sixty years<br>and two blocks</h1>
      <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:17px;line-height:1.55;font-weight:500;">Six decades after Dr. King moved to North Lawndale, GCC sits six blocks away — paying residents $100 to clean their own streets, with 100% of every fee going straight to the worker.</p>
      <p style="margin:0;color:${NEUTRAL_500};font-size:14px;line-height:1.55;">Hi ${greeting} — this is Issue #1. Inside: the field data, the academic studies behind the model, the patterns we're seeing, and how the first 20 cleanups come together.</p>
    </td>
  </tr>

  <!-- §1 — Historical anchor -->
  <tr><td style="padding:24px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;">§1 · The history</p>
    <p style="margin:0 0 16px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      On <strong>January 26, 1966</strong>, Dr. Martin Luther King Jr. carried his bags up to a third-floor walk-up at <strong>1550 South Hamlin Avenue</strong> in North Lawndale and started what he called the &ldquo;action phase&rdquo; of the Chicago Freedom Movement &mdash; the campaign that brought him here to focus on housing conditions and economic opportunity in Chicago neighborhoods. The campaign produced the Summit Agreement, which fed directly into the 1968 Fair Housing Act.
    </p>
    <p style="margin:0 0 16px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      Today, Global Community Cleaners &mdash; the organization sending this email &mdash; is headquartered at <strong>1844 South Harding, 2nd Floor</strong>, a six-block walk from the apartment Dr. King moved into.
    </p>
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 8px 0;">
      <tr><td style="padding:18px 22px;background-color:${BG_EMERALD};border-left:4px solid ${BRAND_GREEN};border-radius:6px;">
        <p style="margin:0;color:${BRAND_GREEN_DARK};font-size:18px;line-height:1.4;font-weight:700;font-style:italic;">Sixty years. Two blocks of distance. Same neighborhood, same work.</p>
      </td></tr>
    </table>
  </td></tr>

  <!-- §2 — The data we already hold (NEW: app + 110 hotspots + heatmap) -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§2 · The data we already hold</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">We're not a cleaning service. We're the field-evidence layer for community cleanliness in North Lawndale.</h2>
    <p style="margin:0 0 18px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      Most organizations talk about a problem. We hold the data on it. The GCC mobile app is live on iOS (Android version in development), and North Lawndale residents have already used it to map &mdash; with photos, GPS, and timestamps &mdash; <strong>110+ trash and dumping hotspots</strong> across the community.
    </p>
  </td></tr>

  <!-- App-live big-number graphic -->
  <tr><td style="padding:8px 32px 0 32px;">
    <a href="${d.appUrl}" style="text-decoration:none;display:block;">
      <img src="${media}/stats/app-live.png" alt="The app is live. 110+ hotspots already mapped by the community." width="536" style="display:block;width:100%;max-width:536px;height:auto;border-radius:12px;" />
    </a>
  </td></tr>

  <!-- Heatmap screenshot -->
  <tr><td style="padding:16px 32px 0 32px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#fafafa;border:1px solid ${NEUTRAL_200};border-radius:12px;">
      <tr><td style="padding:20px 24px 8px 24px;">
        <p style="margin:0 0 6px 0;color:${NEUTRAL_500};font-size:11px;font-weight:700;letter-spacing:0.14em;text-transform:uppercase;">Live heatmap · GCC mobile app</p>
        <p style="margin:0 0 12px 0;color:${NEUTRAL_900};font-size:15px;line-height:1.55;">Every red cluster below is a real, photographed hotspot a resident reported. The denser the heat, the more accumulation that block has seen.</p>
      </td></tr>
      <tr><td style="padding:0 24px 20px 24px;" align="center">
        <img src="${media}/heatmap.png" alt="GCC app heatmap of North Lawndale showing 110+ reported hotspots" width="400" style="display:block;width:100%;max-width:400px;height:auto;border-radius:12px;" />
      </td></tr>
    </table>
  </td></tr>

  <!-- §3 — Conditions photos -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§3 · What 110 hotspots actually look like</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">We documented it because reading it doesn't do it justice.</h2>
    <p style="margin:0 0 18px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      A small sample from the field. Three of the 110+ photographs residents have logged in the app — all in or immediately around North Lawndale, all captured in the last several weeks.
    </p>
  </td></tr>

  <tr><td style="padding:0 32px 0 32px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
      <tr>
        <td width="33%" style="padding:0 4px 0 0;vertical-align:top;">
          <img src="${media}/condition-1.jpg" alt="Dumped cardboard pile next to vacant lot" width="170" style="display:block;width:100%;max-width:170px;height:auto;border-radius:8px;" />
          <p style="margin:6px 2px 0 2px;color:${NEUTRAL_500};font-size:11px;line-height:1.4;font-style:italic;">Dumped cardboard, residential block.</p>
        </td>
        <td width="33%" style="padding:0 2px;vertical-align:top;">
          <img src="${media}/condition-2.jpg" alt="Dumped construction debris on a vacant lot" width="170" style="display:block;width:100%;max-width:170px;height:auto;border-radius:8px;" />
          <p style="margin:6px 2px 0 2px;color:${NEUTRAL_500};font-size:11px;line-height:1.4;font-style:italic;">Construction debris on vacant land.</p>
        </td>
        <td width="33%" style="padding:0 0 0 4px;vertical-align:top;">
          <img src="${media}/condition-3.jpg" alt="Trash accumulation along a fenceline corridor" width="170" style="display:block;width:100%;max-width:170px;height:auto;border-radius:8px;" />
          <p style="margin:6px 2px 0 2px;color:${NEUTRAL_500};font-size:11px;line-height:1.4;font-style:italic;">Wind-driven accumulation along fenceline.</p>
        </td>
      </tr>
    </table>
  </td></tr>

  <!-- §4 — Patterns we noticed -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§4 · Patterns the data reveals</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">110 photographs add up to a small body of urban evidence. Two patterns jumped out.</h2>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:18px 0 0 0;">
      <tr><td style="padding:18px 22px;background-color:#fefce8;border-left:4px solid ${ACCENT_GOLD};border-radius:6px;">
        <p style="margin:0 0 6px 0;color:#854d0e;font-size:12px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;">Pattern 1 · Wind-driven fenceline accumulation</p>
        <p style="margin:0 0 8px 0;color:${NEUTRAL_900};font-size:15px;line-height:1.55;">Fencelines bordering vacant fields and parking lots are catching disproportionate trash &mdash; not because residents dump there, but because <strong>wind funnels light debris into them</strong> and nothing breaks the pattern.</p>
        <p style="margin:0;color:${NEUTRAL_700};font-size:14px;line-height:1.55;"><strong>Long-term fix:</strong> simple windbreak plantings or screen fabric along the worst fencelines, plus a cleanup cadence keyed to wind events. Recoverable infrastructure investment, not a cleanup forever-loop.</p>
      </td></tr>
    </table>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:14px 0 0 0;">
      <tr><td style="padding:18px 22px;background-color:#fefce8;border-left:4px solid ${ACCENT_GOLD};border-radius:6px;">
        <p style="margin:0 0 6px 0;color:#854d0e;font-size:12px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;">Pattern 2 · Walkway corridors without trash receptacles</p>
        <p style="margin:0 0 8px 0;color:${NEUTRAL_900};font-size:15px;line-height:1.55;">Several major pedestrian corridors in North Lawndale have <strong>no public trash cans for blocks at a time</strong>. Hotspot density along these corridors is measurably higher than in zones with built-in receptacles.</p>
        <p style="margin:0;color:${NEUTRAL_700};font-size:14px;line-height:1.55;"><strong>Long-term fix:</strong> targeted bin placement at high-foot-traffic intersections &mdash; either a city ask, a property-owner partnership, or a sponsored bin program. We can name the corridors. We have the data.</p>
      </td></tr>
    </table>

    <p style="margin:18px 0 0 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;font-style:italic;">
      This is the real differentiator. We clean the blocks, and we identify what infrastructure could stop the next round of accumulation before it starts.
    </p>
  </td></tr>

  <!-- §5 — Why this works (academic data) -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§5 · Why this works</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">A randomized citywide trial &mdash; the gold standard &mdash; proved this model works.</h2>
    <p style="margin:0 0 18px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      Charles Branas's team at Penn ran a cluster-randomized trial across Philadelphia, comparing <strong>greened lots</strong>, trash-removal-only lots, and untouched controls over 18 months. The results, published in <em>PNAS</em> and <em>JAMA Network Open</em>, are some of the strongest evidence in the urban-policy literature for any intervention at any cost.
    </p>
  </td></tr>

  <!-- Branas RCT graphic -->
  <tr><td style="padding:8px 32px 0 32px;">
    <img src="${media}/stats/branas-rct.png" alt="Branas trial results: 29.1% drop in gun assaults, 41.5% fewer depression reports, 68.7% drop for low-income residents" width="536" style="display:block;width:100%;max-width:536px;height:auto;border-radius:12px;" />
  </td></tr>

  <!-- ROI graphic -->
  <tr><td style="padding:14px 32px 0 32px;">
    <img src="${media}/stats/roi.png" alt="$7.43 in additional municipal tax revenue per public dollar spent on greening" width="536" style="display:block;width:100%;max-width:536px;height:auto;border-radius:12px;" />
  </td></tr>

  <tr><td style="padding:16px 32px 0 32px;">
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      The newer research goes further. A 2019 meta-analysis in <em>Social Science &amp; Medicine</em> found that the original broken-windows hypothesis works mostly as a downstream signal of <strong>collective efficacy</strong> &mdash; whether neighbors feel any agency over their block. Robert Sampson's Chicago research showed that neighborhoods scoring high on collective efficacy run roughly <strong>40% lower</strong> crime rates, even when income, race, and visible disorder look similar on paper.
    </p>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;font-weight:600;">
      The lesson, repeated across every modern study: invest in the people on the block, and the block follows.
    </p>
  </td></tr>

  <!-- §6 — North Lawndale data -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§6 · The neighborhood we're working in</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">The municipal data on North Lawndale isn't subtle.</h2>
  </td></tr>

  <tr><td style="padding:14px 32px 0 32px;">
    <img src="${media}/stats/life-expectancy-gap.png" alt="20.7-year life-expectancy gap: 87.3 in The Loop vs 66.6 in West Garfield Park" width="536" style="display:block;width:100%;max-width:536px;height:auto;border-radius:12px;" />
  </td></tr>

  <tr><td style="padding:16px 32px 0 32px;">
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      Black Chicagoans died <strong>10.6 years earlier</strong> than non-Black Chicagoans in 2023 (CDPH). North Lawndale's per-capita violent-crime rate runs roughly <strong>3&times; the citywide rate</strong>; it sits on the city's official list of areas &ldquo;most affected by violence&rdquo; (UIC Great Cities Institute, 2022). This is the gap a $100 block fee, paid to the resident on the block, is built to start closing.
    </p>
  </td></tr>

  <!-- §7 — Founder -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§7 · Meet the founder</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">Shaquille Tate built it because he was tired of seeing it.</h2>
    <p style="margin:0 0 16px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      Born at Mt. Sinai Hospital in North Lawndale. Raised on the blocks running from <strong>15th Street to Ridgeway</strong>. Drives Divvy across the entire city for his day job &mdash; which means he sees what every Chicago neighborhood looks like from the curb. Asked on a 2026 radio interview how the idea found him, he answered:
    </p>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 14px 0;">
      <tr><td style="padding:16px 20px;background-color:#fefce8;border-left:4px solid ${ACCENT_GOLD};border-radius:6px;">
        <p style="margin:0;color:${NEUTRAL_900};font-size:16px;line-height:1.7;font-style:italic;">&ldquo;I had to come back to the North Lawndale community, and I'm walking past abandoned houses &mdash; boards on the windows, broken windows, trash everywhere on the floor.&rdquo;</p>
      </td></tr>
    </table>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 14px 0;">
      <tr><td style="padding:16px 20px;background-color:#fefce8;border-left:4px solid ${ACCENT_GOLD};border-radius:6px;">
        <p style="margin:0;color:${NEUTRAL_900};font-size:16px;line-height:1.7;font-style:italic;">&ldquo;What if somebody could get all these kids to clean up their block? And if they were to clean up their block, to get paid to clean up their block.&rdquo;</p>
      </td></tr>
    </table>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 16px 0;">
      <tr><td style="padding:16px 20px;background-color:#fefce8;border-left:4px solid ${ACCENT_GOLD};border-radius:6px;">
        <p style="margin:0;color:${NEUTRAL_900};font-size:16px;line-height:1.7;font-style:italic;">&ldquo;I'm employing elders and I'm employing youth and I'm paying them a hundred dollars to clean up their blocks. I want to start with a 10-block radius&hellip; This is more than just picking up trash.&rdquo;</p>
      </td></tr>
    </table>

    <p style="margin:0 0 16px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      He's right. It is about cleaning, but it's also about cash, dignity, ownership, and the simple fact that <strong>the people best equipped to take care of a block are the people who live on it</strong> &mdash; if someone is willing to pay them like the work matters. Because it does.
    </p>
  </td></tr>

  <!-- §8 — The model -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§8 · How the model works</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">Plain on purpose.</h2>
  </td></tr>

  <tr><td style="padding:14px 32px 0 32px;">
    <img src="${media}/stats/block-model.png" alt="$100 cleans one block. 100% of it goes to the resident doing the work." width="536" style="display:block;width:100%;max-width:536px;height:auto;border-radius:12px;" />
  </td></tr>

  <tr><td style="padding:18px 32px 0 32px;">
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      One participant per block. Elders <em>and</em> youth &mdash; the model is intergenerational by design. Before-and-after photos to every sponsor; block status logged in our system. The same measurement discipline that turned greening from a hunch into peer-reviewed practice.
    </p>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      A <strong>$100 block sponsorship</strong> gets your name on our public sponsor wall. A <strong>$2,000+ sponsorship</strong> unlocks the recognition tier &mdash; name on the wall, digital certificate of appreciation, 501(c)(3) tax receipt, monthly newsletter feature, and social-media recognition across our channels.
    </p>
  </td></tr>

  <!-- §9 — Anyone, anywhere can participate -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§9 · Anyone, anywhere can participate</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">You don't have to live in Chicago to put a hotspot on the map.</h2>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      The GCC app is global. Any user, anywhere in the world, can <strong>spot</strong> illegal dumping, blight, or unsafe conditions in their own neighborhood, photograph it, and add it to the map. Other users earn points by <strong>verifying</strong> a spot is real, still there, or has been resolved. The data layer compounds with every contribution.
    </p>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:16px;line-height:1.65;">
      We're building this into a <strong>points system worth participating in</strong>. We are actively working with local businesses to back the points with rewards our community actually wants:
    </p>
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 14px 0;">
      <tr><td style="padding:14px 18px;background-color:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;">
        <p style="margin:0 0 4px 0;color:#0c4a6e;font-size:12px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;">Reward types in development</p>
        <p style="margin:0;color:${NEUTRAL_900};font-size:15px;line-height:1.65;">🏟️ Sports &amp; concert tickets &nbsp;·&nbsp; 🎁 Gift cards from neighborhood businesses &nbsp;·&nbsp; 🍽️ Restaurant credits &nbsp;·&nbsp; 🛍️ Discounts from partner retailers &nbsp;·&nbsp; 💎 Redeemable points across our internal network</p>
      </td></tr>
    </table>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 16px 0;">
      <tr><td style="padding:18px 22px;background:linear-gradient(135deg,${BRAND_GREEN_DARK} 0%,${BRAND_GREEN} 100%);border-radius:10px;">
        <p style="margin:0 0 6px 0;color:${ACCENT_GOLD};font-size:11px;font-weight:700;letter-spacing:0.16em;text-transform:uppercase;">Local businesses, this is your invitation</p>
        <p style="margin:0 0 8px 0;color:#ffffff;font-size:16px;line-height:1.5;font-weight:600;">If you can put real value behind these points &mdash; tickets, gift cards, table reservations, in-store credits &mdash; we will put your brand directly in front of the residents already cleaning their own streets.</p>
        <p style="margin:0;color:#ffffff;font-size:14px;line-height:1.55;opacity:0.92;">It's the cleanest community-affairs ROI in Chicago: documented impact, photographable proof, audience that lives right where you sell. Email <a href="mailto:hello@globalcommunitycleaners.org" style="color:${ACCENT_GOLD};text-decoration:underline;">hello@globalcommunitycleaners.org</a> with the subject line <strong>&ldquo;Partner on points&rdquo;</strong>.</p>
      </td></tr>
    </table>
  </td></tr>

  <!-- §10 — The ask -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 4px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§10 · The ask</p>
    <h2 style="margin:0 0 16px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">If this resonates, here is exactly what to do with that.</h2>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 12px 0;">
      <tr>
        <td align="center" style="padding:0 4px 8px 0;width:50%;">
          <a href="${d.appUrl}/sponsor" style="display:block;padding:14px 16px;background-color:${BRAND_GREEN};color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;border-radius:8px;text-align:center;">Sponsor a block — $100</a>
        </td>
        <td align="center" style="padding:0 0 8px 4px;width:50%;">
          <a href="${d.appUrl}/sponsor?amount=2000" style="display:block;padding:14px 16px;background-color:${NEUTRAL_900};color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;border-radius:8px;text-align:center;">Recognized sponsor — $2,000</a>
        </td>
      </tr>
    </table>

    <p style="margin:8px 0 14px 0;color:${NEUTRAL_700};font-size:14px;line-height:1.65;">
      <strong>$100</strong> = one cleanup, one paid resident, one before-and-after photo set in your inbox.<br>
      <strong>$2,000</strong> = your name on our public wall, a digital certificate, a 501(c)(3) tax receipt, a feature in this newsletter, and social-media recognition.
    </p>
    <p style="margin:0 0 0 0;color:${NEUTRAL_700};font-size:14px;line-height:1.65;">
      <strong>Forward this email</strong> to one foundation officer, one community-affairs lead at a Chicago employer, and one neighbor. Real change happens on inbox-by-inbox referrals.
    </p>
  </td></tr>

  <!-- §11 — What's next -->
  <tr><td style="padding:32px 32px 0 32px;">
    <p style="margin:0 0 8px 0;color:${BRAND_GREEN};font-size:11px;font-weight:700;letter-spacing:0.18em;text-transform:uppercase;border-top:2px solid ${NEUTRAL_200};padding-top:24px;">§11 · What's next</p>
    <h2 style="margin:0 0 14px 0;color:${NEUTRAL_900};font-size:24px;line-height:1.25;font-weight:700;">Right now we're lining up the first 20 cleanups.</h2>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:15px;line-height:1.65;">
      Twenty blocks &mdash; twenty paid residents &mdash; <strong>20% of our way to the 100-block goal</strong> we set for the North Lawndale pilot. One hundred blocks cleaned, one hundred community members earning real money on their own street. That's the floor, not the ceiling.
    </p>
    <p style="margin:0 0 14px 0;color:${NEUTRAL_500};font-size:14px;line-height:1.6;font-style:italic;">
      The first 20 are pending a soft commitment we're closing with a local partner; we'll name them in the next issue once the funding has cleared and the work is underway. In the meantime, every $100 block sponsorship we receive between now and then funds an additional cleanup on top of those 20.
    </p>

    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 18px 0;">
      <tr><td style="padding:18px 22px;background-color:${BG_EMERALD};border:1px solid #bbf7d0;border-radius:8px;">
        <p style="margin:0 0 4px 0;color:${BRAND_GREEN_DARK};font-size:12px;font-weight:700;letter-spacing:0.12em;text-transform:uppercase;">Goal · North Lawndale pilot</p>
        <p style="margin:0 0 10px 0;color:${NEUTRAL_900};font-size:18px;line-height:1.4;font-weight:700;">100 blocks cleaned · 100 community members paid</p>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:0;">
          <tr>
            <td width="20%" style="background-color:${BRAND_GREEN};height:18px;border-radius:9px 0 0 9px;text-align:center;">
              <div style="color:#ffffff;font-size:11px;font-weight:700;line-height:18px;">20%</div>
            </td>
            <td width="80%" style="background-color:#ffffff;height:18px;border-radius:0 9px 9px 0;border:1px solid #bbf7d0;border-left:none;"></td>
          </tr>
        </table>
        <p style="margin:8px 0 0 0;color:${NEUTRAL_500};font-size:12px;">First 20 cleanups: in preparation. Sponsor a block to put it on a resident's street.</p>
      </td></tr>
    </table>

    <p style="margin:0 0 14px 0;color:${NEUTRAL_700};font-size:14px;line-height:1.65;">
      <strong>In June</strong> we publish the first block-by-block before-and-after report &mdash; which streets we cleaned, who cleaned them, the photo evidence. <strong>In July</strong> we feature the residents who claimed those first cleanups: names, photos, blocks.
    </p>
    <p style="margin:0 0 18px 0;color:${NEUTRAL_900};font-size:15px;line-height:1.6;font-weight:600;">In the meantime &mdash; Dr. King's apartment is six blocks away. The work continues.</p>
    <p style="margin:0 0 32px 0;color:${NEUTRAL_500};font-size:13px;font-style:italic;">&mdash; The team at Global Community Cleaners NFP</p>
  </td></tr>

  <!-- Footer -->
  <tr>
    <td style="padding:24px 32px;background-color:#fafafa;border-top:1px solid ${NEUTRAL_200};text-align:center;">
      <p style="margin:0 0 6px 0;color:${NEUTRAL_700};font-size:12px;line-height:1.6;"><strong>Global Community Cleaners NFP</strong></p>
      <p style="margin:0 0 6px 0;color:${NEUTRAL_500};font-size:12px;line-height:1.6;">1844 S. Harding Ave, 2nd Floor · Chicago, IL 60623</p>
      <p style="margin:0 0 12px 0;color:${NEUTRAL_500};font-size:12px;line-height:1.6;">501(c)(3) tax-exempt nonprofit · EIN ${process.env.GCC_EIN || '00-0000000'}</p>
      <p style="margin:0;color:${NEUTRAL_500};font-size:11px;line-height:1.6;">
        You are receiving this because you signed up at globalcommunitycleaners.org or made a contribution.<br>
        <a href="${d.unsubscribeUrl}" style="color:${NEUTRAL_500};text-decoration:underline;">Unsubscribe</a> · <a href="${d.appUrl}" style="color:${NEUTRAL_500};text-decoration:underline;">Visit our site</a>
      </p>
    </td>
  </tr>

</table>

</td></tr></table>

</body>
</html>`

  const text = `Sixty years and two blocks
=============================
Issue #1 · May 1, 2026 · The Founder's Letter

Hi ${greeting},

This is the first newsletter from Global Community Cleaners. Three things: where we come from, what we already know about our neighborhood, and what we're going to do about it.

§1 · THE HISTORY
On January 26, 1966, Dr. Martin Luther King Jr. moved into 1550 S. Hamlin Avenue in North Lawndale to begin the "action phase" of the Chicago Freedom Movement. His campaign produced the Summit Agreement, which fed directly into the 1968 Fair Housing Act.

Today, Global Community Cleaners is headquartered at 1844 S. Harding, 2nd Floor — a six-block walk from the apartment Dr. King moved into.

Sixty years. Two blocks of distance. Same neighborhood, same work.

§2 · THE DATA WE ALREADY HOLD
We're not a cleaning service — we're the field-evidence layer for community cleanliness in North Lawndale. The GCC mobile app is live, and residents have already mapped 110+ trash and dumping hotspots — with photos, GPS, and timestamps.

§3 · WHAT 110 HOTSPOTS ACTUALLY LOOK LIKE
Dumped cardboard on residential blocks. Construction debris on vacant lots. Wind-driven trash accumulation along fencelines. All photographed by residents, all in or near North Lawndale, all in recent weeks.

§4 · PATTERNS THE DATA REVEALS
- Pattern 1 — Wind-driven fenceline accumulation. Fencelines next to vacant fields and lots are catching disproportionate trash because wind funnels light debris into them. Long-term fix: simple windbreak plantings or screen fabric, plus cleanup cadence keyed to wind events.
- Pattern 2 — Walkway corridors without trash receptacles. Several major pedestrian corridors have no public bins for blocks at a time; hotspot density there is measurably higher. Long-term fix: targeted bin placement at high-foot-traffic intersections.

We clean the blocks. We also identify what infrastructure could stop the next round of accumulation before it starts.

§5 · WHY THIS WORKS
A 2018 randomized citywide trial in Philadelphia (Branas et al., PNAS) showed that greening 541 vacant lots cut nearby gun assaults by 29.1% and dropped depression rates 41.5% (68.7% for residents below the poverty line). Wharton's analysis: $7.43 in tax-revenue return per public dollar spent.

The newer evidence runs through "collective efficacy" — Sampson's Chicago research shows neighborhoods scoring high on it run ~40% lower crime rates. Invest in the people on the block, and the block follows.

§6 · THE NEIGHBORHOOD WE WORK IN
- Life expectancy: 87.3 in the Loop, 66.6 in adjacent West Garfield Park — a 20-year gap inside the same city. (CDPH 2025)
- Citywide gap: Black Chicagoans died 10.6 years earlier than non-Black Chicagoans in 2023.
- Violent crime: ~3x the citywide rate. (UIC Great Cities Institute 2022)

§7 · MEET THE FOUNDER
Shaquille Tate, born at Mt. Sinai Hospital in North Lawndale, grew up on the blocks from 15th to Ridgeway:

  "I had to come back to the North Lawndale community, and I'm walking past abandoned houses — boards on the windows, broken windows, trash everywhere on the floor."

  "What if somebody could get all these kids to clean up their block? And if they were to clean up their block, to get paid to clean up their block."

  "I'm employing elders and I'm employing youth and I'm paying them a hundred dollars to clean up their blocks. I want to start with a 10-block radius. This is more than just picking up trash."

§8 · HOW THE MODEL WORKS
$100 cleans one block. 100% goes to the resident. One participant per block, gate to crest, both sides, including the alley. Same-day pay. Before-and-after photos to sponsors. Elders and youth — intergenerational by design.

§9 · THE ASK
1. Sponsor a block: $100. ${d.appUrl}/sponsor
2. Become a recognized sponsor: $2,000. Name on the wall, certificate, tax receipt, newsletter feature.
3. Forward this email to one foundation officer, one community-affairs lead, and one neighbor.

§10 · WHAT'S NEXT
In June we publish the first block-by-block before-and-after report. In July we'll feature the residents who claimed the first cleanups — names, photos, the blocks they took on.

In the meantime, Dr. King's apartment is six blocks away. The work continues.

— The team at Global Community Cleaners NFP

---
Global Community Cleaners NFP
1844 S. Harding Ave, 2nd Floor, Chicago, IL 60623
501(c)(3) tax-exempt nonprofit · EIN ${process.env.GCC_EIN || '00-0000000'}

Unsubscribe: ${d.unsubscribeUrl}
`

  return { html, text, subject }
}
