'use client'

import { useState, useRef, useEffect } from 'react'
import { cn } from '@/lib/utils'

export interface FileUploadProps {
  label: string
  hint: string
  file: File | null
  onFileSelect: (file: File | null) => void
  accept?: string
}

export function FileUpload({
  label,
  hint,
  file,
  onFileSelect,
  accept = 'image/*,.pdf',
}: FileUploadProps) {
  const [isDragging, setIsDragging] = useState(false)
  const [sizeWarning, setSizeWarning] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // Check file size and warn if large (will be compressed, but good to know)
  useEffect(() => {
    if (file) {
      const sizeMB = file.size / 1024 / 1024
      if (sizeMB > 5) {
        setSizeWarning(`Large file (${sizeMB.toFixed(1)}MB) - will be compressed for upload`)
      } else {
        setSizeWarning(null)
      }
    } else {
      setSizeWarning(null)
    }
  }, [file])

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = () => {
    setIsDragging(false)
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    const droppedFile = e.dataTransfer.files[0]
    if (droppedFile) {
      onFileSelect(droppedFile)
    }
  }

  const handleRemove = (e: React.MouseEvent) => {
    e.stopPropagation()
    onFileSelect(null)
    if (inputRef.current) {
      inputRef.current.value = ''
    }
  }

  return (
    <div className="space-y-2">
      <label className="block text-sm font-medium text-neutral-700">
        {label}
      </label>
      <p className="text-xs text-neutral-500 mb-3">
        {hint}
      </p>
      <label
        className={cn(
          'block border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition-all duration-200',
          isDragging && 'border-brand-gold-400 bg-brand-gold-50 scale-[1.02]',
          !isDragging && !file && 'border-neutral-300 hover:border-brand-gold-400 hover:bg-neutral-50',
          file && 'border-brand-green-400 bg-brand-green-50'
        )}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          className="hidden"
          onChange={(e) => onFileSelect(e.target.files?.[0] || null)}
        />
        {file ? (
          <div className="animate-scale-in">
            <div className="flex items-center justify-center gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center">
                <svg className="w-5 h-5 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
              </div>
              <div className="flex-1 text-left min-w-0">
                <p className="text-sm font-medium text-neutral-900 truncate">{file.name}</p>
                <p className="text-xs text-neutral-500">
                  {file.size > 1024 * 1024
                    ? `${(file.size / 1024 / 1024).toFixed(1)} MB`
                    : `${(file.size / 1024).toFixed(1)} KB`}
                </p>
              </div>
              <button
                type="button"
                onClick={handleRemove}
                className="p-2 rounded-lg hover:bg-red-100 text-neutral-400 hover:text-red-600 transition-colors"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </button>
            </div>
            {sizeWarning && (
              <p className="mt-2 text-xs text-amber-600 flex items-center gap-1">
                <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                {sizeWarning}
              </p>
            )}
          </div>
        ) : (
          <div className="py-2">
            <div className={cn(
              'w-14 h-14 mx-auto mb-3 rounded-xl flex items-center justify-center transition-colors',
              isDragging ? 'bg-brand-gold-100' : 'bg-neutral-100'
            )}>
              <svg
                className={cn(
                  'w-7 h-7 transition-colors',
                  isDragging ? 'text-brand-gold-600' : 'text-neutral-400'
                )}
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                />
              </svg>
            </div>
            <p className="text-sm text-neutral-600 font-medium">
              {isDragging ? 'Drop file here' : 'Tap to upload or drag & drop'}
            </p>
            <p className="text-xs text-neutral-400 mt-1">
              PNG, JPG or PDF up to 10MB
            </p>
          </div>
        )}
      </label>
    </div>
  )
}
