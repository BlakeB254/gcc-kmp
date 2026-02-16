'use client'

import { useEffect, useState, useRef } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'

interface Checkpoint {
  name: string
  type: 'corner_start' | 'midpoint' | 'alley' | 'corner_end'
  coordinates: [number, number]
  radius: number
  instructions: string
}

interface Photo {
  checkpointType: string
  timing: 'before' | 'after'
  imageData: string
  latitude: number
  longitude: number
  capturedAt: string
}

interface JobData {
  id: string
  jobNumber: string
  block: {
    code: string
    displayName: string
    checkpoints: Checkpoint[]
    payoutAmount: number
  }
  status: string
  expiresAt: string
}

const CHECKPOINT_ORDER: Checkpoint['type'][] = ['corner_start', 'midpoint', 'alley', 'corner_end']

export default function JobExecutionPage() {
  const params = useParams()
  const router = useRouter()
  const jobId = params.id as string

  const [job, setJob] = useState<JobData | null>(null)
  const [currentCheckpointIndex, setCurrentCheckpointIndex] = useState(0)
  const [currentTiming, setCurrentTiming] = useState<'before' | 'after'>('before')
  const [photos, setPhotos] = useState<Photo[]>([])
  const [isCapturing, setIsCapturing] = useState(false)
  const [location, setLocation] = useState<{ lat: number; lng: number } | null>(null)
  const [locationError, setLocationError] = useState<string | null>(null)
  const [gpsPath, setGpsPath] = useState<[number, number][]>([])
  const [isSubmitting, setIsSubmitting] = useState(false)

  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const streamRef = useRef<MediaStream | null>(null)

  // Get current checkpoint
  const currentCheckpoint = job?.block.checkpoints.find(
    (cp) => cp.type === CHECKPOINT_ORDER[currentCheckpointIndex]
  )

  // Check if we have both photos for current checkpoint
  const hasBeforePhoto = photos.some(
    (p) => p.checkpointType === CHECKPOINT_ORDER[currentCheckpointIndex] && p.timing === 'before'
  )
  const hasAfterPhoto = photos.some(
    (p) => p.checkpointType === CHECKPOINT_ORDER[currentCheckpointIndex] && p.timing === 'after'
  )

  // Total progress
  const totalPhotosNeeded = 8
  const photosTaken = photos.length
  const progress = (photosTaken / totalPhotosNeeded) * 100

  useEffect(() => {
    // Fetch job data
    // TODO: Implement actual API call
    setJob({
      id: jobId,
      jobNumber: 'JOB-20240115-001',
      block: {
        code: 'NL-002',
        displayName: 'Roosevelt Rd: Spaulding to Sawyer',
        payoutAmount: 10000,
        checkpoints: [
          {
            name: 'Corner at Spaulding Ave',
            type: 'corner_start',
            coordinates: [-87.7085, 41.8666],
            radius: 15,
            instructions: 'Start at the corner. Take a wide shot showing the sidewalk and curb.',
          },
          {
            name: 'Midpoint',
            type: 'midpoint',
            coordinates: [-87.7095, 41.8666],
            radius: 15,
            instructions: 'Stand at the middle of the block. Capture both directions.',
          },
          {
            name: 'Alley Entrance',
            type: 'alley',
            coordinates: [-87.7095, 41.8669],
            radius: 20,
            instructions: 'Position at the alley entrance. Show the alley condition.',
          },
          {
            name: 'Corner at Sawyer Ave',
            type: 'corner_end',
            coordinates: [-87.7105, 41.8666],
            radius: 15,
            instructions: 'End at this corner. Take a wide shot looking back at the block.',
          },
        ],
      },
      status: 'claimed',
      expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    })

    // Start watching GPS
    startGPSTracking()

    return () => {
      stopCamera()
    }
  }, [jobId])

  const startGPSTracking = () => {
    if (!navigator.geolocation) {
      setLocationError('GPS not available on this device')
      return
    }

    navigator.geolocation.watchPosition(
      (position) => {
        const newLocation = {
          lat: position.coords.latitude,
          lng: position.coords.longitude,
        }
        setLocation(newLocation)
        setLocationError(null)

        // Add to GPS path
        setGpsPath((prev) => [...prev, [newLocation.lng, newLocation.lat]])
      },
      (error) => {
        setLocationError('Unable to get your location. Please enable GPS.')
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 0,
      }
    )
  }

  const startCamera = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: 'environment', // Use back camera
          width: { ideal: 1920 },
          height: { ideal: 1080 },
        },
      })
      streamRef.current = stream
      if (videoRef.current) {
        videoRef.current.srcObject = stream
      }
      setIsCapturing(true)
    } catch (error) {
      console.error('Failed to start camera:', error)
      alert('Unable to access camera. Please grant camera permissions.')
    }
  }

  const stopCamera = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop())
      streamRef.current = null
    }
    setIsCapturing(false)
  }

  const capturePhoto = () => {
    if (!videoRef.current || !canvasRef.current || !location) return

    const video = videoRef.current
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')

    if (!ctx) return

    canvas.width = video.videoWidth
    canvas.height = video.videoHeight
    ctx.drawImage(video, 0, 0)

    const imageData = canvas.toDataURL('image/jpeg', 0.8)

    const newPhoto: Photo = {
      checkpointType: CHECKPOINT_ORDER[currentCheckpointIndex],
      timing: currentTiming,
      imageData,
      latitude: location.lat,
      longitude: location.lng,
      capturedAt: new Date().toISOString(),
    }

    setPhotos((prev) => [...prev, newPhoto])
    stopCamera()

    // Move to next state
    if (currentTiming === 'before') {
      // Stay on same checkpoint, switch to after
      setCurrentTiming('after')
    } else {
      // Move to next checkpoint
      if (currentCheckpointIndex < CHECKPOINT_ORDER.length - 1) {
        setCurrentCheckpointIndex((prev) => prev + 1)
        setCurrentTiming('before')
      }
    }
  }

  const handleSubmit = async () => {
    if (photos.length !== 8) {
      alert('Please complete all 8 photos before submitting')
      return
    }

    setIsSubmitting(true)

    try {
      const res = await fetch('/api/jobs/submit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jobId,
          workerId: 'current-worker-id', // TODO: Get from auth
          photos,
          gpsPath,
        }),
      })

      if (res.ok) {
        router.push(`/worker/job/${jobId}/completed`)
      } else {
        const error = await res.json()
        alert(error.message)
      }
    } catch (error) {
      alert('Failed to submit job. Please try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!job) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-neutral-900">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-white" />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-neutral-900 text-white flex flex-col">
      {/* Header */}
      <header className="p-4 flex items-center justify-between">
        <Link href="/worker/dashboard" className="p-2 -ml-2">
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </Link>
        <div className="text-center">
          <p className="text-xs text-neutral-400">Job {job.jobNumber}</p>
          <p className="font-medium">{job.block.code}</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-neutral-400">Payout</p>
          <p className="font-bold text-brand-gold-400">$100</p>
        </div>
      </header>

      {/* Progress bar */}
      <div className="px-4 mb-4">
        <div className="flex justify-between text-xs text-neutral-400 mb-1">
          <span>Progress</span>
          <span>{photosTaken}/8 photos</span>
        </div>
        <div className="h-2 bg-neutral-800 rounded-full overflow-hidden">
          <div
            className="h-full bg-brand-gold-500 transition-all duration-300"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      {/* Camera/Photo View */}
      <div className="flex-1 relative">
        {isCapturing ? (
          <>
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted
              className="absolute inset-0 w-full h-full object-cover"
            />
            <canvas ref={canvasRef} className="hidden" />

            {/* Capture button */}
            <div className="absolute bottom-8 left-0 right-0 flex justify-center">
              <button
                onClick={capturePhoto}
                className="w-20 h-20 rounded-full bg-white border-4 border-neutral-300 flex items-center justify-center"
              >
                <div className="w-14 h-14 rounded-full bg-white" />
              </button>
            </div>

            {/* Photo type indicator */}
            <div className="absolute top-4 left-4 right-4">
              <div
                className={`inline-flex px-3 py-1 rounded-full text-sm font-medium ${
                  currentTiming === 'before'
                    ? 'bg-red-500/90 text-white'
                    : 'bg-green-500/90 text-white'
                }`}
              >
                {currentTiming === 'before' ? 'BEFORE Cleaning' : 'AFTER Cleaning'}
              </div>
            </div>
          </>
        ) : (
          <div className="h-full flex flex-col items-center justify-center p-8">
            {/* Checkpoint info */}
            {currentCheckpoint && photosTaken < 8 && (
              <div className="text-center max-w-sm">
                <div className="mb-6">
                  <span className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-brand-gold-500/20 text-brand-gold-400 text-2xl font-bold">
                    {currentCheckpointIndex + 1}
                  </span>
                </div>
                <h2 className="text-xl font-semibold mb-2">{currentCheckpoint.name}</h2>
                <p className="text-neutral-400 mb-6">{currentCheckpoint.instructions}</p>

                {/* GPS status */}
                {location ? (
                  <div className="flex items-center justify-center gap-2 text-brand-green-400 mb-6">
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    <span className="text-sm">GPS locked</span>
                  </div>
                ) : locationError ? (
                  <div className="text-red-400 text-sm mb-6">{locationError}</div>
                ) : (
                  <div className="flex items-center justify-center gap-2 text-neutral-400 mb-6">
                    <div className="animate-spin h-4 w-4 border-2 border-neutral-400 border-t-transparent rounded-full" />
                    <span className="text-sm">Acquiring GPS...</span>
                  </div>
                )}

                {/* What photo to take */}
                <div
                  className={`p-4 rounded-lg mb-6 ${
                    currentTiming === 'before'
                      ? 'bg-red-500/10 border border-red-500/30'
                      : 'bg-green-500/10 border border-green-500/30'
                  }`}
                >
                  <p className="text-sm">
                    {currentTiming === 'before'
                      ? '📷 Take BEFORE photo first, then clean this area'
                      : '✨ Area cleaned? Take AFTER photo now'}
                  </p>
                </div>

                <button
                  onClick={startCamera}
                  disabled={!location}
                  className="btn-primary w-full disabled:opacity-50"
                >
                  {currentTiming === 'before' ? 'Take Before Photo' : 'Take After Photo'}
                </button>
              </div>
            )}

            {/* All done - ready to submit */}
            {photosTaken === 8 && (
              <div className="text-center max-w-sm">
                <div className="mb-6">
                  <span className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-brand-green-500/20 text-brand-green-400">
                    <svg className="w-10 h-10" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </span>
                </div>
                <h2 className="text-2xl font-bold mb-2">All Photos Complete!</h2>
                <p className="text-neutral-400 mb-8">
                  Great work! Submit your job for review. Once approved, $100 will be sent to your
                  bank account instantly.
                </p>
                <button
                  onClick={handleSubmit}
                  disabled={isSubmitting}
                  className="btn-primary w-full disabled:opacity-50"
                >
                  {isSubmitting ? 'Submitting...' : 'Submit Job for Review'}
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Checkpoint indicators */}
      <div className="p-4 bg-neutral-800">
        <div className="flex justify-between">
          {CHECKPOINT_ORDER.map((type, index) => {
            const checkpoint = job.block.checkpoints.find((cp) => cp.type === type)
            const hasBefore = photos.some((p) => p.checkpointType === type && p.timing === 'before')
            const hasAfter = photos.some((p) => p.checkpointType === type && p.timing === 'after')
            const isComplete = hasBefore && hasAfter
            const isCurrent = index === currentCheckpointIndex

            return (
              <div
                key={type}
                className={`flex flex-col items-center gap-1 ${
                  isCurrent ? 'text-white' : isComplete ? 'text-brand-green-400' : 'text-neutral-500'
                }`}
              >
                <div
                  className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-medium ${
                    isComplete
                      ? 'bg-brand-green-500 text-white'
                      : isCurrent
                      ? 'bg-brand-gold-500 text-white'
                      : 'bg-neutral-700'
                  }`}
                >
                  {isComplete ? '✓' : index + 1}
                </div>
                <span className="text-[10px] text-center">{type.split('_')[0]}</span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
