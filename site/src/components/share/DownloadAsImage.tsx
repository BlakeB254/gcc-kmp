'use client'

import { useRef, useCallback } from 'react'
import { toPng } from 'html-to-image'

interface DownloadAsImageProps {
  children: React.ReactNode
  filename?: string
  onDownload?: () => void
  className?: string
}

export function DownloadAsImage({
  children,
  filename = 'gcc-card',
  onDownload,
  className = '',
}: DownloadAsImageProps) {
  const cardRef = useRef<HTMLDivElement>(null)

  const handleDownload = useCallback(async () => {
    if (!cardRef.current) return

    try {
      const dataUrl = await toPng(cardRef.current, {
        cacheBust: true,
        pixelRatio: 2, // Higher quality for sharing
        backgroundColor: 'transparent',
      })

      // Create download link
      const link = document.createElement('a')
      link.download = `${filename}.png`
      link.href = dataUrl
      link.click()

      onDownload?.()
    } catch (err) {
      console.error('Failed to generate image:', err)
    }
  }, [filename, onDownload])

  return (
    <div className={className}>
      <div ref={cardRef}>{children}</div>
    </div>
  )
}

// Hook for more flexible usage
export function useDownloadAsImage() {
  const download = useCallback(
    async (
      element: HTMLElement | null,
      filename: string = 'gcc-card'
    ): Promise<string | null> => {
      if (!element) return null

      try {
        const dataUrl = await toPng(element, {
          cacheBust: true,
          pixelRatio: 2,
          backgroundColor: 'transparent',
        })

        const link = document.createElement('a')
        link.download = `${filename}.png`
        link.href = dataUrl
        link.click()

        return dataUrl
      } catch (err) {
        console.error('Failed to generate image:', err)
        return null
      }
    },
    []
  )

  const toDataUrl = useCallback(
    async (element: HTMLElement | null): Promise<string | null> => {
      if (!element) return null

      try {
        return await toPng(element, {
          cacheBust: true,
          pixelRatio: 2,
          backgroundColor: 'transparent',
        })
      } catch (err) {
        console.error('Failed to generate image:', err)
        return null
      }
    },
    []
  )

  return { download, toDataUrl }
}
