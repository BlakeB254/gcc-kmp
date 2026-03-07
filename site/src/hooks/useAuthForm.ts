'use client'

import { useState, FormEvent } from 'react'
import { useRouter } from 'next/navigation'

interface UseAuthFormOptions {
  apiEndpoint: string
  successRedirect: string
  extraBody?: Record<string, any>
}

export function useAuthForm({ apiEndpoint, successRedirect, extraBody }: UseAuthFormOptions) {
  const router = useRouter()
  const [formData, setFormData] = useState<Record<string, string>>({})
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const updateField = (name: string, value: string) => {
    setFormData(prev => ({ ...prev, [name]: value }))
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setError(null)
    try {
      const res = await fetch(apiEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...formData, ...extraBody }),
      })
      const data = await res.json()
      if (!res.ok) {
        setError(data.error || 'Something went wrong')
        return data
      }
      router.push(successRedirect)
      return data
    } catch (err) {
      setError('Network error. Please try again.')
      return null
    } finally {
      setIsLoading(false)
    }
  }

  return { formData, updateField, isLoading, error, handleSubmit, setError }
}
