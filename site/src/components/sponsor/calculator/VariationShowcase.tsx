'use client'

import Image from 'next/image'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import {
  GCC_BASELINE,
  SPONSORS,
  SPONSOR_ORDER,
  type Sponsor,
  type SponsorSlug,
  type ZoomRegion,
} from '@/components/sponsor/calculator/variationData'

interface VariationShowcaseProps {
  title: string
  subtitle: string
  description: string
  benefits: string[]
  /** Path to the GCC-only base scene (pre-wipe) */
  baseImageSrc: string
  /** Map of sponsor slug -> image path for the co-branded variations */
  variationImageSrcs: Record<SponsorSlug, string>
  imageAlt?: string
  accentColor?: 'gold' | 'green' | 'brown' | 'sky'
  icon?: React.ReactNode
  /** Optional: square fractional region of the image to show as a PiP zoom */
  zoomRegion?: ZoomRegion
}

const accentStyles = {
  gold: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
  },
  green: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-emerald-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
  },
  brown: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-700 text-white',
  },
  sky: {
    bg: 'bg-gradient-to-br from-brand-blue-100 to-blue-100',
    border: 'border-brand-blue-200',
    text: 'text-brand-blue-800',
    badge: 'bg-brand-blue-500 text-white',
  },
}

const WIPE_DURATION_MS = 1200
const SPONSOR_CYCLE_MS = 2800

export function VariationShowcase({
  title,
  subtitle,
  description,
  benefits,
  baseImageSrc,
  variationImageSrcs,
  imageAlt,
  accentColor = 'gold',
  icon,
  zoomRegion,
}: VariationShowcaseProps) {
  const styles = accentStyles[accentColor]
  const rootRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const [hasEntered, setHasEntered] = useState(false)
  const [wipeProgress, setWipeProgress] = useState(0)
  const [activeIdx, setActiveIdx] = useState<number>(0)
  const [isPaused, setIsPaused] = useState(false)
  const [isDragging, setIsDragging] = useState(false)
  const [prefersReducedMotion, setPrefersReducedMotion] = useState(false)

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    setPrefersReducedMotion(mq.matches)
    const handler = (e: MediaQueryListEvent) => setPrefersReducedMotion(e.matches)
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  useEffect(() => {
    const node = rootRef.current
    if (!node || hasEntered) return
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) {
            setHasEntered(true)
            io.disconnect()
          }
        }
      },
      { threshold: 0.35 }
    )
    io.observe(node)
    return () => io.disconnect()
  }, [hasEntered])

  // Play a GCC-baseline → sponsor wipe every time the active sponsor changes
  // (including the initial mount when it enters the viewport). Using
  // useLayoutEffect guarantees that wipeProgress is reset to 0 in the same
  // commit that flips to the new sponsor's image — so the new variation is
  // never briefly visible before the wipe starts.
  //
  // The info panel derives `showingBaseline` from the same wipeProgress, so
  // the panel swap is naturally synchronized with the image reveal: during
  // the first half the panel shows GCC, during the second half it shows the
  // incoming sponsor. Wipe + branding change = one synchronized event.
  useLayoutEffect(() => {
    if (!hasEntered) return
    if (prefersReducedMotion) {
      setWipeProgress(1)
      return
    }
    setWipeProgress(0)
    let raf = 0
    const start = performance.now()
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / WIPE_DURATION_MS)
      setWipeProgress(t)
      if (t < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [hasEntered, activeIdx, prefersReducedMotion])

  // Cycle to the next sponsor after the wipe finishes + a breathing pause.
  useEffect(() => {
    if (!hasEntered || isPaused || isDragging || prefersReducedMotion) return
    const interval = setInterval(() => {
      setActiveIdx((prev) => (prev + 1) % SPONSOR_ORDER.length)
    }, SPONSOR_CYCLE_MS + WIPE_DURATION_MS)
    return () => clearInterval(interval)
  }, [hasEntered, isPaused, isDragging, prefersReducedMotion])

  const activeSlug: SponsorSlug = SPONSOR_ORDER[activeIdx] ?? SPONSOR_ORDER[0]
  const activeSponsor: Sponsor = SPONSORS[activeSlug]
  const activeImageSrc = variationImageSrcs[activeSlug]

  // Show GCC baseline panel when wipe is less than halfway (user dragged back)
  const showingBaseline = wipeProgress < 0.5
  const panelSponsor: Sponsor = showingBaseline ? GCC_BASELINE : activeSponsor

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    setIsDragging(true)
    setIsPaused(true)
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isDragging) return
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const x = Math.max(0, Math.min(rect.width, e.clientX - rect.left))
    setWipeProgress(x / rect.width)
  }

  const handlePointerUp = () => {
    if (!isDragging) return
    setIsDragging(false)
    setWipeProgress(1)
    setIsPaused(false)
  }

  const handleChipClick = (slug: SponsorSlug) => {
    const idx = SPONSOR_ORDER.indexOf(slug)
    if (idx < 0) return
    if (idx === activeIdx) return
    setActiveIdx(idx)
    setIsPaused(true)
    // Wipe plays automatically via the activeIdx-dependent layout effect.
  }

  const inset = useMemo(() => `inset(0 ${(1 - wipeProgress) * 100}% 0 0)`, [wipeProgress])

  return (
    <div
      ref={rootRef}
      className={cn('rounded-2xl border overflow-hidden mb-6', styles.border, styles.bg)}
    >
      <div className="flex flex-col">
        <div
          ref={containerRef}
          className="relative w-full aspect-[3/2] overflow-hidden select-none bg-neutral-900"
        >
          <Image
            src={baseImageSrc}
            alt={imageAlt ? `${imageAlt} — GCC-only baseline` : title}
            fill
            className="object-cover object-center"
            sizes="(max-width: 1024px) 100vw, (max-width: 1400px) 90vw, 720px"
            priority={false}
          />
          <div
            className="absolute inset-0"
            style={{
              clipPath: inset,
              WebkitClipPath: inset,
              // No CSS transition — the wipe is driven per-frame via
              // requestAnimationFrame in the layout effect above. A CSS
              // transition here would smooth over the rAF updates and
              // produce visible stutter.
              transition: 'none',
            }}
          >
            <Image
              src={activeImageSrc}
              alt={`${imageAlt ?? title} co-branded with ${activeSponsor.name}. Campaign: ${activeSponsor.campaign}.`}
              fill
              className="object-cover object-center"
              sizes="(max-width: 1024px) 100vw, (max-width: 1400px) 90vw, 720px"
            />
          </div>
          <div className="absolute inset-x-0 bottom-0 h-1/3 bg-gradient-to-t from-black/40 to-transparent pointer-events-none" />
          {hasEntered && !prefersReducedMotion && (
            <div
              role="slider"
              aria-label="Wipe between GCC-only baseline and co-branded variation"
              aria-valuenow={Math.round(wipeProgress * 100)}
              aria-valuemin={0}
              aria-valuemax={100}
              tabIndex={0}
              className="absolute top-0 bottom-0 w-1 bg-white/90 cursor-ew-resize shadow-lg"
              style={{ left: `calc(${wipeProgress * 100}% - 2px)` }}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerUp}
              onPointerCancel={handlePointerUp}
              onKeyDown={(e) => {
                if (e.key === 'ArrowLeft') setWipeProgress((v) => Math.max(0, v - 0.1))
                if (e.key === 'ArrowRight') setWipeProgress((v) => Math.min(1, v + 0.1))
              }}
            >
              <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-6 h-6 rounded-full bg-white shadow-md flex items-center justify-center">
                <span className="text-xs text-neutral-700">⇔</span>
              </div>
            </div>
          )}
          {icon && (
            <div className="absolute top-3 left-3 w-10 h-10 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow">
              {icon}
            </div>
          )}
          {zoomRegion && (
            <PipZoom
              imageSrc={activeImageSrc}
              region={zoomRegion}
              altText={`Zoomed view of ${activeSponsor.name} branding: ${zoomRegion.label}`}
            />
          )}
        </div>

        {/* Sponsor info panel — immediately below the image so it swaps
            in lockstep with the image cycle, giving sponsors a visible
            second co-branded surface. */}
        <SponsorInfoPanel sponsor={panelSponsor} isBaseline={showingBaseline} />

        {/* Chip row — lets users lock a variation. Kept tight to the info
            panel so the sponsor-cycle cluster reads as a single unit. */}
        <div className={cn('flex flex-wrap items-center gap-2 px-6 pt-4 pb-2', styles.bg)}>
          <span className="text-xs text-neutral-500 mr-1">Preview sponsor:</span>
          {SPONSOR_ORDER.map((slug, i) => {
            const sponsor = SPONSORS[slug]
            const isActive = i === activeIdx
            return (
              <button
                key={slug}
                onClick={() => handleChipClick(slug)}
                aria-pressed={isActive}
                className={cn(
                  'px-3 py-1 rounded-full text-xs font-semibold border transition-colors',
                  isActive
                    ? `${sponsor.brandColors.bg} ${sponsor.brandColors.text} border-transparent`
                    : 'bg-white/80 text-neutral-700 border-neutral-300 hover:border-neutral-400'
                )}
              >
                {sponsor.name}
              </button>
            )
          })}
        </div>

        <div className="flex-1 p-6 pt-4">
          <div className="flex items-start justify-between mb-3">
            <div>
              <span className={cn('text-xs font-semibold uppercase tracking-wider', styles.text)}>
                {subtitle}
              </span>
              <h3 className="text-xl font-bold text-neutral-900 mt-1">{title}</h3>
            </div>
            <span className={cn('text-xs font-bold px-3 py-1 rounded-full', styles.badge)}>
              High Impact
            </span>
          </div>

          <p className="text-neutral-700 mb-4">{description}</p>

          <div className="space-y-2 mb-4">
            <p className="text-sm font-semibold text-neutral-900">Why sponsors love this:</p>
            <ul className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {benefits.map((benefit, index) => (
                <li key={index} className="flex items-start gap-2 text-sm text-neutral-700">
                  <span className={cn('flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-xs', styles.badge)}>
                    {index + 1}
                  </span>
                  {benefit}
                </li>
              ))}
            </ul>
          </div>

          <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-[11px] text-amber-900 leading-relaxed">
            <p className="font-semibold mb-1">⚠ AI-generated mockup — illustrative only</p>
            <p>
              Preview sponsors (Monday Coffee, Del Kar Pharmacy, Barney&apos;s New Life, Black Panther Party Cubs) are examples.
              The branded scenes above are AI-generated approximations produced from each sponsor&apos;s reference logo —
              some lettering and placement will not be pixel-accurate. For a real sponsorship, we work directly with your
              team to co-produce the final photography and creative using your actual logos, campaign messaging, and brand
              guidelines.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Infographic picture-in-picture zoom.
 *
 * Two coordinated elements render as siblings inside the image container:
 *   1. A source-region indicator drawn over the main image at the ZoomRegion
 *      coordinates, marked "A".
 *   2. A PiP viewer docked in the top-right, also marked "A", showing the
 *      same region magnified.
 *
 * Color choice: amber is used on purpose. The underlying scene is dominated
 * by a kelly-green vest, so a green indicator disappears into the subject.
 * Amber has maximum contrast against both green and the surrounding street.
 *
 * Coordinate system: ZoomRegion.size is a fraction of the image *width*.
 * The source indicator uses `aspectRatio: 1 / 1` so it stays square inside
 * the 3:2 container without manual height math. The PiP translate uses
 * `-x * 100%` / `-y * 100%` because CSS translate percentages resolve
 * against the translated element's own dimensions (not the parent).
 */
function PipZoom({ imageSrc, region, altText }: { imageSrc: string; region: ZoomRegion; altText: string }) {
  const scale = 1 / region.size
  return (
    <>
      {/* Source-region indicator drawn on top of the main image */}
      <div
        className="absolute border-[3px] border-amber-400 rounded-sm shadow-[0_0_0_2px_rgba(0,0,0,0.5),inset_0_0_0_1px_rgba(0,0,0,0.4)] pointer-events-none"
        style={{
          left: `${region.x * 100}%`,
          top: `${region.y * 100}%`,
          width: `${region.size * 100}%`,
          aspectRatio: '1 / 1',
        }}
        aria-hidden="true"
      >
        <span className="absolute -top-3 -left-3 bg-amber-400 text-neutral-900 text-xs font-black w-7 h-7 rounded-full shadow-lg flex items-center justify-center border-2 border-white">
          A
        </span>
      </div>

      {/* PiP viewer docked top-right, higher up in frame */}
      <div className="absolute top-3 right-3 w-48 aspect-[3/2] rounded-lg overflow-hidden shadow-2xl ring-[3px] ring-amber-400 bg-neutral-900 pointer-events-none">
        <span className="absolute -top-2 -left-2 z-10 bg-amber-400 text-neutral-900 text-xs font-black w-7 h-7 rounded-full shadow-lg flex items-center justify-center border-2 border-white">
          A
        </span>
        <div className="absolute inset-0 overflow-hidden">
          <div
            className="absolute top-0 left-0"
            style={{
              width: `${scale * 100}%`,
              height: `${scale * 100}%`,
              transform: `translate(${-region.x * 100}%, ${-region.y * 100}%)`,
            }}
          >
            <Image
              src={imageSrc}
              alt={altText}
              fill
              sizes="192px"
              className="object-cover"
            />
          </div>
        </div>
        <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/90 to-transparent pt-3 pb-1 text-center">
          <span className="text-[10px] font-bold text-white uppercase tracking-wider">🔍 {region.label}</span>
        </div>
      </div>
    </>
  )
}

function SponsorInfoPanel({ sponsor, isBaseline }: { sponsor: Sponsor; isBaseline: boolean }) {
  return (
    <div
      className={cn(
        'flex items-start gap-4 px-6 py-4 border-b border-white/10 transition-colors duration-500',
        sponsor.brandColors.bg,
        sponsor.brandColors.text
      )}
      aria-label={isBaseline ? 'GCC baseline sponsor info' : `Sponsor preview: ${sponsor.name}`}
    >
      <div className="relative w-20 h-20 flex-shrink-0 rounded-lg bg-white/10 backdrop-blur-sm p-2 flex items-center justify-center">
        <Image
          src={sponsor.logoPath}
          alt={`${sponsor.name} logo`}
          width={72}
          height={72}
          className="object-contain"
          onError={(e) => {
            e.currentTarget.style.display = 'none'
          }}
        />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-baseline gap-2 flex-wrap">
          <span className={cn('text-[10px] font-bold uppercase tracking-widest opacity-80')}>
            {isBaseline ? 'GCC Standard' : 'Sponsored by'}
          </span>
          <div className={cn('h-1 flex-1 min-w-[20px] rounded-full opacity-50', sponsor.brandColors.accent)} />
        </div>
        <h4 className="text-lg font-bold mt-0.5 truncate">{sponsor.name}</h4>
        <p className="text-sm opacity-90 mt-0.5">{sponsor.tagline}</p>
        <div className="mt-2 space-y-0.5 text-[11px] opacity-90">
          <p>
            <span className="font-semibold">Campaign:</span> {sponsor.campaign}
          </p>
          <p>
            <span className="font-semibold">Address:</span> {sponsor.address}
          </p>
          <div className="flex flex-wrap gap-x-3 gap-y-1">
            <span>🌐 {sponsor.websiteUrl}</span>
            {sponsor.instagramHandle && <span>📷 {sponsor.instagramHandle}</span>}
          </div>
        </div>
      </div>
    </div>
  )
}
