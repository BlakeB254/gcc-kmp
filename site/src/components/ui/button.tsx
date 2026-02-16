import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center whitespace-nowrap rounded-lg text-base font-semibold ring-offset-background transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 active:scale-[0.98]',
  {
    variants: {
      variant: {
        // Gold primary - main CTA
        default:
          'bg-brand-gold-600 text-white shadow-sm hover:bg-brand-gold-700 active:bg-brand-gold-800',
        // Brown secondary - alternative actions
        secondary:
          'border-2 border-brand-brown-700 bg-transparent text-brand-brown-700 hover:bg-brand-brown-50 active:bg-brand-brown-100',
        // Green success - confirmations
        success:
          'bg-brand-green-600 text-white shadow-sm hover:bg-brand-green-700 active:bg-brand-green-800',
        // Sky info/link style
        link: 'text-brand-sky-600 underline-offset-4 hover:underline',
        // Destructive
        destructive:
          'bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90',
        // Outline
        outline:
          'border border-input bg-background shadow-sm hover:bg-accent hover:text-accent-foreground',
        // Ghost
        ghost: 'hover:bg-accent hover:text-accent-foreground',
      },
      size: {
        default: 'h-12 px-6 py-3',
        sm: 'h-9 rounded-md px-4 py-2 text-sm',
        lg: 'h-14 rounded-xl px-8 py-4 text-lg',
        icon: 'h-10 w-10',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button'
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = 'Button'

export { Button, buttonVariants }
