interface StepHeaderProps {
  step: number
  title: string
  description: string
}

export function StepHeader({ step, title, description }: StepHeaderProps) {
  return (
    <div className="bg-neutral-50 border border-neutral-200 rounded-xl px-5 py-4 mb-6">
      <div className="flex items-center gap-3">
        <span className="w-9 h-9 rounded-full bg-brand-green-500 text-white flex items-center justify-center text-sm font-bold shadow-md flex-shrink-0">
          {step}
        </span>
        <div>
          <h2 className="text-xl font-bold text-neutral-900">{title}</h2>
          <p className="text-sm text-neutral-500 mt-0.5">{description}</p>
        </div>
      </div>
    </div>
  )
}
