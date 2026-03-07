import { NextResponse } from 'next/server'

export function apiSuccess(data: any, status = 200) {
  return NextResponse.json(data, { status })
}

export function apiError(message: string, status: number, extras?: Record<string, any>) {
  return NextResponse.json({ error: message, ...extras }, { status })
}

export function withErrorHandler(handler: (req: Request) => Promise<NextResponse>) {
  return async (req: Request) => {
    try {
      return await handler(req)
    } catch (error: any) {
      console.error('API Error:', error)
      return apiError(
        error.message || 'Internal server error',
        error.status || 500
      )
    }
  }
}
