import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
  {
    variants: {
      variant: {
        default:
          'border-transparent bg-primary text-primary-foreground shadow',
        secondary:
          'border-transparent bg-secondary text-secondary-foreground',
        success:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        warning:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        info:
          'border-transparent bg-brand-blue-100 text-brand-blue-800',
        destructive:
          'border-transparent bg-destructive text-destructive-foreground shadow',
        outline: 'text-foreground',
        // Worker status badges
        pending:
          'border-transparent bg-amber-100 text-amber-800',
        approved:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        available:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        claimed:
          'border-transparent bg-brand-blue-100 text-brand-blue-800',
        completed:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        // Member badges
        founder:
          'border-transparent bg-gradient-to-r from-brand-green-500 to-brand-green-600 text-white shadow-sm',
        earlyMember:
          'border-transparent bg-brand-green-100 text-brand-green-800',
        verified:
          'border-transparent bg-brand-blue-50 text-brand-blue-700 border border-brand-blue-200',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  }
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  )
}

export { Badge, badgeVariants }
