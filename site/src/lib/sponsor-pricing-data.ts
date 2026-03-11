/**
 * GCC Sponsor Pricing Data
 *
 * Core Principle: $100 donated = $100 to worker (basic cleaning)
 * Add-ons fund GCC operations, equipment, and creative services
 */

// Base pricing (in cents)
// IMPORTANT: $100 per block ALWAYS goes to the worker - this is non-negotiable
export const BASE_BLOCK_PRICE = 10000 // $100 - 100% to worker
export const WORKER_PAYOUT_PER_BLOCK = 10000 // $100 - worker always receives this

// Standard job parameters
export const STANDARD_JOB_HOURS = 4 // Max 4 hours per worker per block
export const OVERTIME_RATE_PER_HOUR = 2500 // $25/hr extra for overtime (to worker)

// Volume discount tiers - ONLY applies to add-ons, NOT to base worker pay
// Workers always get $100/block regardless of volume
export const volumeDiscounts = [
  { minBlocks: 1, maxBlocks: 9, pricePerBlock: 10000, addonDiscount: 0, label: 'Standard' },
  { minBlocks: 10, maxBlocks: 49, pricePerBlock: 10000, addonDiscount: 5, label: '5% off add-ons' },
  { minBlocks: 50, maxBlocks: 99, pricePerBlock: 10000, addonDiscount: 10, label: '10% off add-ons' },
  { minBlocks: 100, maxBlocks: null, pricePerBlock: 10000, addonDiscount: 15, label: '15% off add-ons' },
] as const

// Multi-worker options for deeper/faster cleans
export const multiWorkerOptions = {
  singleWorker: {
    id: 'single_worker',
    name: 'Standard (1 Worker)',
    description: 'One worker, up to 4 hours per block',
    workers: 1,
    priceMultiplier: 1,
    workerPayout: 10000, // $100 to worker
  },
  twoWorkers: {
    id: 'two_workers',
    name: 'Team Clean (2 Workers)',
    description: 'Two workers for faster/deeper clean',
    workers: 2,
    priceMultiplier: 2,
    workerPayout: 20000, // $200 total ($100 each)
  },
  threeWorkers: {
    id: 'three_workers',
    name: 'Deep Team (3 Workers)',
    description: 'Three workers for thorough deep clean',
    workers: 3,
    priceMultiplier: 3,
    workerPayout: 30000, // $300 total ($100 each)
  },
} as const

// Extended time options (beyond 4 hours)
export const extendedTimeOptions = {
  standard: {
    id: 'standard_time',
    name: 'Standard (up to 4 hrs)',
    hours: 4,
    extraCost: 0,
    workerBonus: 0,
  },
  extended6hr: {
    id: 'extended_6hr',
    name: 'Extended (6 hrs)',
    hours: 6,
    extraCost: 5000, // $50 extra to sponsor
    workerBonus: 5000, // $50 extra to worker (2hrs × $25/hr)
  },
  fullDay: {
    id: 'full_day',
    name: 'Full Day (8 hrs)',
    hours: 8,
    extraCost: 10000, // $100 extra to sponsor
    workerBonus: 10000, // $100 extra to worker (4hrs × $25/hr)
  },
} as const

// Worker branding add-ons (per block pricing in cents)
export const workerBrandingAddons = {
  vests: {
    id: 'branded_vests',
    name: 'Branded Vests',
    description: 'High-vis vest with sponsor logo (front + back)',
    pricePerBlock: 1500, // $15
    category: 'worker_branding',
  },
  shirts: {
    id: 'branded_shirts',
    name: 'Branded T-Shirts',
    description: 'Quality cotton tee with sponsor designs',
    pricePerBlock: 1200, // $12
    category: 'worker_branding',
  },
  hats: {
    id: 'branded_hats',
    name: 'Branded Caps',
    description: 'Baseball caps with embroidered logo',
    pricePerBlock: 800, // $8
    category: 'worker_branding',
  },
  fullKit: {
    id: 'full_uniform_kit',
    name: 'Full Uniform Kit',
    description: 'Vest + shirt + hat combo',
    pricePerBlock: 3000, // $30
    category: 'worker_branding',
    isBundle: true,
    includes: ['vests', 'shirts', 'hats'],
  },
} as const

// Equipment branding add-ons
export const equipmentBrandingAddons = {
  brooms: {
    id: 'branded_brooms',
    name: 'Branded Brooms',
    description: 'Logo wrap on broom handles',
    pricePerBlock: 1000, // $10
    category: 'equipment_branding',
  },
  rakes: {
    id: 'branded_rakes',
    name: 'Branded Rakes',
    description: 'Logo wrap on rake handles',
    pricePerBlock: 1000, // $10
    category: 'equipment_branding',
  },
  trashBags: {
    id: 'branded_bags',
    name: 'Branded Trash Bags',
    description: 'Custom printed bags (great for photos)',
    pricePerBlock: 500, // $5
    category: 'equipment_branding',
  },
  carts: {
    id: 'branded_carts',
    name: 'Branded Carts/Bins',
    description: 'Rolling bins with sponsor wrap',
    pricePerBlock: 2500, // $25
    category: 'equipment_branding',
  },
  fullEquipmentKit: {
    id: 'full_equipment_kit',
    name: 'Full Equipment Kit',
    description: 'All equipment branded',
    pricePerBlock: 4000, // $40
    category: 'equipment_branding',
    isBundle: true,
    includes: ['brooms', 'rakes', 'trashBags', 'carts'],
  },
} as const

// Street-level advertising add-ons
export const streetAdvertisingAddons = {
  blockSigns: {
    id: 'block_signs',
    name: 'Block Signs',
    description: '"This block cleaned by [Sponsor]" yard signs',
    pricePerBlock: 2500, // $25
    category: 'street_advertising',
  },
  banners: {
    id: 'banner_display',
    name: 'Banner Display',
    description: 'Large banner at block entrance during cleaning',
    pricePerBlock: 5000, // $50
    category: 'street_advertising',
  },
  aFrames: {
    id: 'a_frame_signage',
    name: 'A-Frame Signage',
    description: 'Sidewalk A-frames with sponsor message',
    pricePerBlock: 3500, // $35
    category: 'street_advertising',
  },
  sidewalkStencil: {
    id: 'sidewalk_stencil',
    name: 'Sidewalk Stencil',
    description: 'Pressure-washed logo (eco-safe, 12-72hr visibility)',
    pricePerBlock: 7500, // $75
    category: 'street_advertising',
  },
  chalkArt: {
    id: 'chalk_art',
    name: 'Chalk Art',
    description: 'Artist-created chalk murals with branding',
    pricePerBlock: 10000, // $100
    category: 'street_advertising',
  },
} as const

// Digital presence add-ons
export const digitalPresenceAddons = {
  socialTag: {
    id: 'social_media_tag',
    name: 'Social Media Tag',
    description: '@sponsor in before/after posts',
    pricePerBlock: 1000, // $10
    category: 'digital_presence',
  },
  logoOnPhotos: {
    id: 'logo_on_photos',
    name: 'Logo on Photos',
    description: 'Watermark on all job photos',
    pricePerBlock: 1500, // $15
    category: 'digital_presence',
  },
  videoClip: {
    id: 'video_clip',
    name: 'Video Clip',
    description: '15-sec worker video thanking sponsor',
    pricePerBlock: 5000, // $50
    category: 'digital_presence',
  },
  instagramStory: {
    id: 'instagram_story',
    name: 'Instagram Story Feature',
    description: 'Dedicated story with swipe-up',
    pricePerBlock: 2500, // $25
    category: 'digital_presence',
  },
} as const

// Enhanced cleaning services
export const enhancedCleaningServices = {
  deepClean: {
    id: 'deep_clean',
    name: 'Deep Clean',
    description: 'Extended cleaning time, detail work',
    pricePerBlock: 5000, // $50
    category: 'enhanced_cleaning',
  },
  pressureWashing: {
    id: 'pressure_washing',
    name: 'Pressure Washing',
    description: 'Sidewalks, stoops, building facades',
    pricePerBlock: 15000, // $150
    category: 'enhanced_cleaning',
  },
  graffitiRemoval: {
    id: 'graffiti_removal',
    name: 'Graffiti Removal',
    description: 'Professional removal products',
    pricePerBlock: 20000, // $200
    category: 'enhanced_cleaning',
  },
  illegalDumpingCleanup: {
    id: 'illegal_dumping',
    name: 'Illegal Dumping Cleanup',
    description: 'Heavy debris, bulk pickup coordination',
    pricePerBlock: 30000, // $300
    category: 'enhanced_cleaning',
  },
} as const

// Seasonal services
export const seasonalServices = {
  snowRemoval: {
    id: 'snow_removal',
    name: 'Snow Removal',
    description: 'Shoveling, salt application',
    pricePerBlock: 7500, // $75
    category: 'seasonal',
  },
  leafRemoval: {
    id: 'leaf_removal',
    name: 'Leaf Removal',
    description: 'Fall leaf collection and bagging',
    pricePerBlock: 5000, // $50
    category: 'seasonal',
  },
  springCleanup: {
    id: 'spring_cleanup',
    name: 'Spring Cleanup',
    description: 'Post-winter deep clean',
    pricePerBlock: 10000, // $100
    category: 'seasonal',
  },
} as const

// Green services
export const greenServices = {
  basicLandscaping: {
    id: 'basic_landscaping',
    name: 'Basic Landscaping',
    description: 'Weeding, edging, basic planting',
    pricePerBlock: 10000, // $100
    category: 'green',
  },
  treeWellCare: {
    id: 'tree_well_care',
    name: 'Tree Well Care',
    description: 'Mulching, debris removal around trees',
    pricePerBlock: 7500, // $75
    category: 'green',
  },
  vacantLotMaintenance: {
    id: 'vacant_lot',
    name: 'Vacant Lot Maintenance',
    description: 'Monthly maintenance contract',
    pricePerLot: 20000, // $200
    category: 'green',
  },
  communityGardenSupport: {
    id: 'community_garden',
    name: 'Community Garden Support',
    description: 'Garden bed maintenance',
    pricePerPlot: 15000, // $150
    category: 'green',
  },
} as const

// Business engagement add-ons
export const businessEngagementAddons = {
  flyerDistribution: {
    id: 'flyer_distribution',
    name: 'Flyer Distribution',
    description: 'Sponsor flyers to passersby',
    pricePerBlock: 5000, // $50
    workerBonus: 1000, // $10
    category: 'business_engagement',
    requiresWorkerConsent: true,
  },
  sampleDistribution: {
    id: 'sample_distribution',
    name: 'Sample Distribution',
    description: 'Hand out product samples',
    pricePerBlock: 7500, // $75
    workerBonus: 1500, // $15
    category: 'business_engagement',
    requiresWorkerConsent: true,
  },
  qrCodeCards: {
    id: 'qr_code_cards',
    name: 'QR Code Cards',
    description: "Cards with sponsor's QR code",
    pricePerBlock: 3000, // $30
    workerBonus: 500, // $5
    category: 'business_engagement',
    requiresWorkerConsent: true,
  },
  businessCardDrop: {
    id: 'business_card_drop',
    name: 'Business Card Drop',
    description: 'Leave cards at local businesses',
    pricePerBlock: 4000, // $40
    workerBonus: 1000, // $10
    category: 'business_engagement',
    requiresWorkerConsent: true,
  },
} as const

// Event packages (flat pricing in cents)
export const eventPackages = {
  popUpCrew: {
    id: 'popup_crew',
    name: 'Pop-Up Crew (2hr)',
    description: '3 workers, basic supplies',
    price: 30000, // $300
    workers: 3,
    duration: 2,
    category: 'event',
  },
  standardCrew: {
    id: 'standard_crew',
    name: 'Standard Crew (4hr)',
    description: '5 workers, full supplies',
    price: 50000, // $500
    workers: 5,
    duration: 4,
    category: 'event',
  },
  extendedCrew: {
    id: 'extended_crew',
    name: 'Extended Crew (8hr)',
    description: '5 workers, meals included',
    price: 90000, // $900
    workers: 5,
    duration: 8,
    category: 'event',
  },
  largeEventCrew: {
    id: 'large_event_crew',
    name: 'Large Event Crew',
    description: '10 workers, supervisor, all supplies',
    price: 150000, // $1,500
    workers: 10,
    duration: 8,
    category: 'event',
  },
} as const

// Event types
export const eventTypes = {
  blockParty: {
    id: 'block_party',
    name: 'Block Party Support',
    description: 'Pre-event prep + during + post-cleanup',
    basePrice: 75000, // $750
    category: 'event_type',
  },
  festivalMaintenance: {
    id: 'festival',
    name: 'Festival Maintenance',
    description: 'Roving crew throughout event',
    pricePerDay: 200000, // $2,000/day
    category: 'event_type',
  },
  grandOpening: {
    id: 'grand_opening',
    name: 'Grand Opening',
    description: 'Business storefront + surrounding area',
    basePrice: 50000, // $500
    category: 'event_type',
  },
  corporateVolunteerDay: {
    id: 'corporate_volunteer',
    name: 'Corporate Volunteer Day',
    description: 'GCC workers guide + coordinate volunteers',
    basePrice: 100000, // $1,000
    category: 'event_type',
  },
  photoOpEvent: {
    id: 'photo_op',
    name: 'Photo Op Event',
    description: 'Staged cleaning for sponsor media',
    basePrice: 75000, // $750
    category: 'event_type',
  },
  holidayCleanup: {
    id: 'holiday_cleanup',
    name: 'Holiday Cleanup',
    description: 'Special event (MLK Day, 4th of July, etc.)',
    basePrice: 120000, // $1,200
    category: 'event_type',
  },
} as const

// Media & content production
export const mediaPackages = {
  basicPhoto: {
    id: 'basic_photo',
    name: 'Basic Photo Package',
    description: '20 edited photos, digital delivery',
    price: 50000, // $500
    deliverables: '20 edited photos',
    category: 'media',
  },
  standardPhoto: {
    id: 'standard_photo',
    name: 'Standard Photo Package',
    description: '50 photos, 2-hour shoot, minor staging',
    price: 150000, // $1,500
    deliverables: '50 photos, 2-hour shoot',
    category: 'media',
  },
  premiumPhoto: {
    id: 'premium_photo',
    name: 'Premium Photo Package',
    description: 'Full day shoot, 100+ photos, drone shots',
    price: 350000, // $3,500
    deliverables: 'Full day, 100+ photos, drone',
    category: 'media',
  },
  campaignPhotoSeries: {
    id: 'campaign_photo',
    name: 'Campaign Photo Series',
    description: 'Multi-day, storytelling approach',
    price: 750000, // $7,500
    deliverables: 'Multi-day storytelling series',
    category: 'media',
  },
} as const

export const videoPackages = {
  socialClip: {
    id: 'social_clip',
    name: 'Social Clip',
    description: '30-sec video, mobile-optimized',
    price: 100000, // $1,000
    deliverables: '30-sec social video',
    category: 'video',
  },
  thankYouVideo: {
    id: 'thank_you_video',
    name: 'Thank You Video',
    description: '60-sec with worker testimonials',
    price: 250000, // $2,500
    deliverables: '60-sec testimonial video',
    category: 'video',
  },
  impactStory: {
    id: 'impact_story',
    name: 'Impact Story',
    description: '2-3 min mini-doc style',
    price: 500000, // $5,000
    deliverables: '2-3 min documentary',
    category: 'video',
  },
  commercialSpot: {
    id: 'commercial_spot',
    name: 'Commercial Spot',
    description: '30-sec broadcast-quality',
    price: 1500000, // $15,000
    deliverables: '30-sec broadcast commercial',
    category: 'video',
  },
  brandFilm: {
    id: 'brand_film',
    name: 'Brand Film',
    description: '5-10 min documentary short',
    price: 3500000, // $35,000
    deliverables: '5-10 min documentary',
    category: 'video',
  },
} as const

// Enterprise partnership tiers
// IMPORTANT: Worker pay is ALWAYS $100/block - discounts only apply to included add-on services
export const enterpriseTiers = {
  communityChampion: {
    id: 'community_champion',
    name: 'Community Champion',
    minAmount: 2500000, // $25,000
    maxAmount: 4999999,
    blockRange: '250-500 blocks',
    commitmentPeriod: '6-12 months',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 10, // 10% off add-on services only
    benefits: [
      'Full branded campaign (vests, signs, equipment)',
      'Quarterly impact reports with metrics',
      'Sponsor wall "Gold" recognition',
      '2 social media features/month',
      'Basic photo package',
      'Dedicated account manager',
    ],
    includedAddons: ['vests', 'blockSigns', 'socialTag'],
    category: 'enterprise',
  },
  impactPartner: {
    id: 'impact_partner',
    name: 'Impact Partner',
    minAmount: 5000000, // $50,000
    maxAmount: 9999999,
    blockRange: '500-1,000 blocks',
    commitmentPeriod: '6-12 months',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 15, // 15% off add-on services
    benefits: [
      'Everything in Community Champion',
      'Standard photo + video package',
      'Press release support',
      'Newsletter feature story',
      'Sponsor wall "Platinum" recognition',
      'Option to name a "block" or "corner"',
      'Monthly strategy calls',
    ],
    includedAddons: ['fullKit', 'blockSigns', 'banners', 'socialTag', 'logoOnPhotos'],
    category: 'enterprise',
  },
  documentarySponsor: {
    id: 'documentary_sponsor',
    name: 'Documentary Sponsor',
    minAmount: 10000000, // $100,000
    maxAmount: 24999999,
    blockRange: '1,000+ blocks',
    commitmentPeriod: '12 months',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 20, // 20% off add-on services
    benefits: [
      'Everything in Impact Partner',
      'Professional photography (full campaign)',
      '30-second commercial spot',
      'Behind-the-scenes content',
      'PR coordination with local media',
      'VIP community event invitation',
      'Board advisory opportunity (non-voting)',
      'Logo on GCC website homepage',
    ],
    includedAddons: ['fullKit', 'fullEquipmentKit', 'blockSigns', 'banners'],
    includedMedia: ['standardPhoto', 'thankYouVideo'],
    category: 'enterprise',
  },
  movementPartner: {
    id: 'movement_partner',
    name: 'Movement Partner',
    minAmount: 25000000, // $250,000
    maxAmount: 49999999,
    blockRange: 'Multi-neighborhood campaign',
    commitmentPeriod: '12 months',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 25, // 25% off add-on services
    benefits: [
      'Everything in Documentary Sponsor',
      'Full documentary crew (5-10 min piece)',
      'Social media campaign management',
      'Influencer partnership coordination',
      'Press conference support',
      'Named initiative ("The [Sponsor] Clean Streets Initiative")',
      'Speaking opportunity at community events',
    ],
    includedMedia: ['premiumPhoto', 'impactStory'],
    category: 'enterprise',
  },
  legacySponsor: {
    id: 'legacy_sponsor',
    name: 'Legacy Sponsor',
    minAmount: 50000000, // $500,000
    maxAmount: 99999999,
    blockRange: 'City-wide impact',
    commitmentPeriod: '12+ months',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 30, // 30% off add-on services
    benefits: [
      'Everything in Movement Partner',
      'Multi-week documented campaign',
      'Daily social media content + vlogs',
      'Dedicated videographer on rotation',
      'National media pitch support',
      'Permanent recognition plaque in community center',
      'Annual celebration event in sponsor\'s name',
      'First right of refusal on expansion markets',
    ],
    includedMedia: ['campaignPhotoSeries', 'commercialSpot'],
    category: 'enterprise',
  },
  foundingPartner: {
    id: 'founding_partner',
    name: 'Founding Partner',
    minAmount: 100000000, // $1,000,000
    maxAmount: null,
    blockRange: 'Movement-defining',
    commitmentPeriod: 'Multi-year',
    workerPayPerBlock: 10000, // $100 - always goes to worker
    addonDiscount: 35, // 35% off add-on services
    benefits: [
      'Everything in Legacy Sponsor',
      'Full feature documentary (20-30 min)',
      'Multi-market launch support',
      '"Presented by [Sponsor]" co-branding rights',
      'Exclusive neighborhood naming rights',
      'Historic recognition in all GCC materials',
      'Seat on GCC advisory board',
      'Block party celebration + ribbon cutting',
      'Case study for business schools/media',
      'Annual impact gala sponsorship',
    ],
    includedMedia: ['campaignPhotoSeries', 'brandFilm'],
    category: 'enterprise',
  },
} as const

// Recognition levels (based on total contribution)
export const recognitionLevels = {
  supporter: {
    id: 'supporter',
    name: 'Supporter',
    minAmount: 10000, // $100
    maxAmount: 99999,
    recognition: ['Thank you email', 'Digital certificate'],
  },
  friend: {
    id: 'friend',
    name: 'Friend',
    minAmount: 100000, // $1,000
    maxAmount: 499999,
    recognition: ['Social media mention', 'Sponsor wall listing'],
  },
  advocate: {
    id: 'advocate',
    name: 'Advocate',
    minAmount: 500000, // $5,000
    maxAmount: 999999,
    recognition: ['Newsletter feature', 'Photo package'],
  },
  champion: {
    id: 'champion',
    name: 'Champion',
    minAmount: 1000000, // $10,000
    maxAmount: 2499999,
    recognition: ['Press release', 'Video testimonial'],
  },
  partner: {
    id: 'partner',
    name: 'Partner',
    minAmount: 2500000, // $25,000
    maxAmount: 4999999,
    recognition: ['Named initiative option'],
  },
  leader: {
    id: 'leader',
    name: 'Leader',
    minAmount: 5000000, // $50,000
    maxAmount: 9999999,
    recognition: ['Board advisory seat'],
  },
  visionary: {
    id: 'visionary',
    name: 'Visionary',
    minAmount: 10000000, // $100,000
    maxAmount: 49999999,
    recognition: ['Permanent plaque', 'Event naming'],
  },
  founder: {
    id: 'founder',
    name: 'Founder',
    minAmount: 50000000, // $500,000
    maxAmount: null,
    recognition: ['Documentary', 'Historic recognition'],
  },
} as const

// Recurring plan discounts
export const recurringDiscounts = {
  weekly: 5, // 5% discount
  biweekly: 7, // 7% discount
  monthly: 10, // 10% discount
  quarterly: 15, // 15% discount
  annual: 20, // 20% discount
} as const

// All add-ons grouped by category
export const allAddons = {
  workerBranding: workerBrandingAddons,
  equipmentBranding: equipmentBrandingAddons,
  streetAdvertising: streetAdvertisingAddons,
  digitalPresence: digitalPresenceAddons,
  enhancedCleaning: enhancedCleaningServices,
  seasonal: seasonalServices,
  green: greenServices,
  businessEngagement: businessEngagementAddons,
} as const

export type AddonCategory = keyof typeof allAddons
export type WorkerBrandingAddon = keyof typeof workerBrandingAddons
export type EquipmentBrandingAddon = keyof typeof equipmentBrandingAddons
export type StreetAdvertisingAddon = keyof typeof streetAdvertisingAddons
export type DigitalPresenceAddon = keyof typeof digitalPresenceAddons
export type EnhancedCleaningService = keyof typeof enhancedCleaningServices
export type SeasonalService = keyof typeof seasonalServices
export type GreenService = keyof typeof greenServices
export type BusinessEngagementAddon = keyof typeof businessEngagementAddons
export type EventPackage = keyof typeof eventPackages
export type EventType = keyof typeof eventTypes
export type MediaPackage = keyof typeof mediaPackages
export type VideoPackage = keyof typeof videoPackages
export type EnterpriseTier = keyof typeof enterpriseTiers
export type RecognitionLevel = keyof typeof recognitionLevels
export type MultiWorkerOption = keyof typeof multiWorkerOptions
export type ExtendedTimeOption = keyof typeof extendedTimeOptions
