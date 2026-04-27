'use client'

import { useState, useEffect, useRef, Suspense } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { Logo } from '@/components/brand/Logo'
import { LoadingFallback } from '@/components/shared/LoadingFallback'
import { cn } from '@/lib/utils'
import {
  Upload,
  CheckCircle2,
  Loader2,
  FileText,
  Home,
  AlertCircle,
} from 'lucide-react'

type DocumentType = 'government_id' | 'proof_of_address'

interface DocState {
  uploaded: boolean
  uploading: boolean
  error: string | null
  fileName: string | null
}

const initialDoc: DocState = {
  uploaded: false,
  uploading: false,
  error: null,
  fileName: null,
}

function DocumentsContent() {
  const router = useRouter()
  const [authChecked, setAuthChecked] = useState(false)
  const [govId, setGovId] = useState<DocState>(initialDoc)
  const [proofOfAddress, setProofOfAddress] = useState<DocState>(initialDoc)

  const govInputRef = useRef<HTMLInputElement>(null)
  const poaInputRef = useRef<HTMLInputElement>(null)

  // On mount, fetch /me to (a) confirm we're authed and (b) seed already-uploaded state
  useEffect(() => {
    const load = async () => {
      try {
        const res = await fetch('/api/workers/me', { credentials: 'include' })
        if (res.status === 401) {
          router.replace('/worker/login')
          return
        }
        const data = await res.json()
        const docs = data?.user?.documents
        if (docs?.governmentIdUploaded) {
          setGovId({ uploaded: true, uploading: false, error: null, fileName: 'On file' })
        }
        if (docs?.proofOfAddressUploaded) {
          setProofOfAddress({ uploaded: true, uploading: false, error: null, fileName: 'On file' })
        }
      } catch {
        // Network hiccup — let the user try anyway; the API will re-auth.
      } finally {
        setAuthChecked(true)
      }
    }
    load()
  }, [router])

  const upload = async (documentType: DocumentType, file: File) => {
    const setter = documentType === 'government_id' ? setGovId : setProofOfAddress

    setter({ uploaded: false, uploading: true, error: null, fileName: file.name })

    const fd = new FormData()
    fd.append('documentType', documentType)
    fd.append('file', file)

    try {
      const res = await fetch('/api/workers/upload-document', {
        method: 'POST',
        body: fd,
        credentials: 'include',
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data?.error || 'Upload failed')

      setter({ uploaded: true, uploading: false, error: null, fileName: file.name })
    } catch (err) {
      setter({
        uploaded: false,
        uploading: false,
        error: err instanceof Error ? err.message : 'Upload failed',
        fileName: file.name,
      })
    }
  }

  const handleFileChange = (
    documentType: DocumentType,
    e: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = e.target.files?.[0]
    if (file) void upload(documentType, file)
    // reset so picking the same file again still fires onChange
    e.target.value = ''
  }

  const bothUploaded = govId.uploaded && proofOfAddress.uploaded

  if (!authChecked) {
    return <LoadingFallback />
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 py-golden-lg sm:py-golden-xl px-4">
      <div className="max-w-xl mx-auto">
        <div className="text-center mb-golden-md sm:mb-golden-lg">
          <div className="mb-golden-sm sm:mb-golden-md">
            <Logo size="md" className="mx-auto" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">
            Verify Your Identity
          </h1>
          <p className="text-neutral-600 max-w-md mx-auto">
            Upload two documents so we can pay you safely. Most reviews take{' '}
            <span className="font-semibold text-brand-green-700">24–48 hours</span>.
          </p>
        </div>

        <div className="space-y-5">
          <DocumentCard
            title="Government-Issued ID"
            subtitle="Driver's license, state ID, or passport"
            icon={<FileText className="w-6 h-6" />}
            state={govId}
            inputRef={govInputRef}
            onPick={() => govInputRef.current?.click()}
            onChange={(e) => handleFileChange('government_id', e)}
          />

          <DocumentCard
            title="Proof of Address"
            subtitle="Utility bill, bank statement, or lease (within last 90 days)"
            icon={<Home className="w-6 h-6" />}
            state={proofOfAddress}
            inputRef={poaInputRef}
            onPick={() => poaInputRef.current?.click()}
            onChange={(e) => handleFileChange('proof_of_address', e)}
          />
        </div>

        <div className="mt-8 space-y-4">
          <button
            onClick={() => router.push('/worker/pending')}
            disabled={!bothUploaded}
            className={cn(
              'w-full py-3 px-6 rounded-xl font-semibold text-white transition-all flex items-center justify-center gap-2',
              bothUploaded
                ? 'bg-brand-green-600 hover:bg-brand-green-700 active:bg-brand-green-800 shadow-md hover:shadow-lg'
                : 'bg-neutral-300 cursor-not-allowed',
            )}
          >
            {bothUploaded ? (
              <>
                <CheckCircle2 className="w-5 h-5" />
                Continue to Review
              </>
            ) : (
              'Upload both documents to continue'
            )}
          </button>

          <p className="text-center text-xs text-neutral-500">
            Accepted formats: JPEG, PNG, WebP, or PDF · Max 10MB · Documents are encrypted in transit and only viewed by our verification team.
          </p>

          <div className="text-center">
            <Link href="/" className="text-sm text-neutral-500 hover:text-neutral-700">
              &larr; Save and continue later
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}

interface DocumentCardProps {
  title: string
  subtitle: string
  icon: React.ReactNode
  state: DocState
  inputRef: React.RefObject<HTMLInputElement | null>
  onPick: () => void
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
}

function DocumentCard({ title, subtitle, icon, state, inputRef, onPick, onChange }: DocumentCardProps) {
  return (
    <div
      className={cn(
        'bg-white rounded-2xl border-2 p-5 sm:p-6 transition-colors',
        state.uploaded
          ? 'border-brand-green-300 bg-brand-green-50/40'
          : state.error
            ? 'border-red-300'
            : 'border-neutral-200',
      )}
    >
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/jpg,image/png,image/webp,application/pdf"
        className="hidden"
        onChange={onChange}
      />

      <div className="flex items-start gap-4">
        <div
          className={cn(
            'w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0',
            state.uploaded ? 'bg-brand-green-500 text-white' : 'bg-neutral-100 text-neutral-500',
          )}
        >
          {state.uploaded ? <CheckCircle2 className="w-6 h-6" /> : icon}
        </div>

        <div className="flex-1 min-w-0">
          <h3 className="font-semibold text-neutral-900">{title}</h3>
          <p className="text-sm text-neutral-500 mt-0.5">{subtitle}</p>

          {state.fileName && !state.error && (
            <p className="text-xs text-neutral-500 mt-2 truncate">
              {state.uploaded ? '✓ ' : ''}
              {state.fileName}
            </p>
          )}

          {state.error && (
            <div className="mt-2 flex items-start gap-1.5 text-sm text-red-600">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span>{state.error}</span>
            </div>
          )}
        </div>

        <button
          type="button"
          onClick={onPick}
          disabled={state.uploading}
          className={cn(
            'flex-shrink-0 px-4 py-2 rounded-lg font-medium text-sm transition-colors flex items-center gap-1.5',
            state.uploading
              ? 'bg-neutral-200 text-neutral-500 cursor-not-allowed'
              : state.uploaded
                ? 'bg-white border border-brand-green-300 text-brand-green-700 hover:bg-brand-green-50'
                : 'bg-brand-green-600 text-white hover:bg-brand-green-700',
          )}
        >
          {state.uploading ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" />
              Uploading…
            </>
          ) : state.uploaded ? (
            'Replace'
          ) : (
            <>
              <Upload className="w-4 h-4" />
              Upload
            </>
          )}
        </button>
      </div>
    </div>
  )
}

export default function WorkerDocumentsPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <DocumentsContent />
    </Suspense>
  )
}
