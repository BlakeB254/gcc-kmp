'use client'

import * as React from 'react'
import { Drawer } from 'vaul'
import { cn } from '@/lib/utils'

interface ActionSheetAction {
  label: string
  onClick: () => void
  variant?: 'default' | 'destructive'
  icon?: React.ReactNode
}

interface ActionSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title?: string
  description?: string
  actions: ActionSheetAction[]
  cancelLabel?: string
}

/**
 * ActionSheet provides an iOS-style action sheet for mobile.
 * Uses Vaul drawer for smooth, native-feeling animations.
 */
export function ActionSheet({
  open,
  onOpenChange,
  title,
  description,
  actions,
  cancelLabel = 'Cancel',
}: ActionSheetProps) {
  return (
    <Drawer.Root open={open} onOpenChange={onOpenChange}>
      <Drawer.Portal>
        <Drawer.Overlay className="fixed inset-0 z-50 bg-black/80" />
        <Drawer.Content className="fixed bottom-0 left-0 right-0 z-50 mt-24 flex flex-col rounded-t-[20px] bg-background">
          {/* Drag handle */}
          <div className="mx-auto mt-4 h-1.5 w-12 flex-shrink-0 rounded-full bg-muted" />

          {/* Header */}
          {(title || description) && (
            <div className="border-b border-border px-4 py-3 text-center">
              {title && (
                <Drawer.Title className="text-sm font-medium text-muted-foreground">
                  {title}
                </Drawer.Title>
              )}
              {description && (
                <Drawer.Description className="mt-1 text-xs text-muted-foreground">
                  {description}
                </Drawer.Description>
              )}
            </div>
          )}

          {/* Actions */}
          <div className="p-2">
            {actions.map((action, index) => (
              <button
                key={index}
                onClick={() => {
                  action.onClick()
                  onOpenChange(false)
                }}
                className={cn(
                  'flex w-full items-center justify-center gap-2 rounded-lg px-4 py-3.5 text-base font-medium transition-colors',
                  action.variant === 'destructive'
                    ? 'text-destructive hover:bg-destructive/10'
                    : 'text-foreground hover:bg-accent'
                )}
              >
                {action.icon}
                {action.label}
              </button>
            ))}
          </div>

          {/* Cancel button */}
          <div className="p-2 pt-0 safe-bottom">
            <button
              onClick={() => onOpenChange(false)}
              className="w-full rounded-lg bg-muted px-4 py-3.5 text-base font-semibold text-foreground transition-colors hover:bg-muted/80"
            >
              {cancelLabel}
            </button>
          </div>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  )
}

// Hook for easier action sheet usage
export function useActionSheet() {
  const [isOpen, setIsOpen] = React.useState(false)
  const [config, setConfig] = React.useState<Omit<ActionSheetProps, 'open' | 'onOpenChange'>>({
    actions: [],
  })

  const show = (options: Omit<ActionSheetProps, 'open' | 'onOpenChange'>) => {
    setConfig(options)
    setIsOpen(true)
  }

  const hide = () => setIsOpen(false)

  const ActionSheetComponent = () => (
    <ActionSheet
      open={isOpen}
      onOpenChange={setIsOpen}
      {...config}
    />
  )

  return { show, hide, ActionSheet: ActionSheetComponent }
}
