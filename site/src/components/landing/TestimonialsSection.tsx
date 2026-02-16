export function TestimonialsSection() {
  // Placeholder testimonials - these would be real stories post-launch
  const testimonials = [
    {
      id: 1,
      name: 'Marcus J.',
      role: 'GCC Worker',
      quote:
        "I was skeptical at first, but the money was in my bank account within an hour of finishing. I've cleaned 8 blocks this month and it feels good earning money while making my block look better.",
      avatar: null, // Placeholder for real photo
      blocksCompleted: 8,
    },
    {
      id: 2,
      name: 'Linda W.',
      role: 'Local Business Owner',
      quote:
        "I sponsored the block in front of my store. The difference is incredible. My customers noticed right away, and knowing a neighbor earned that $100 makes it even better.",
      avatar: null,
      blocksFunded: 3,
    },
    {
      id: 3,
      name: 'DeShawn T.',
      role: 'GCC Worker',
      quote:
        "The app walks you through everything. Before photo, clean, after photo. Super simple. And getting paid instantly instead of waiting weeks? That's a game changer.",
      avatar: null,
      blocksCompleted: 12,
    },
  ]

  return (
    <section className="py-24 bg-white">
      <div className="section-container">
        {/* Section header */}
        <div className="text-center mb-16">
          <h2 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-4">
            Community Voices
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto">
            Real stories from workers and sponsors making a difference in North Lawndale.
          </p>
        </div>

        {/* Testimonials grid */}
        <div className="grid md:grid-cols-3 gap-8">
          {testimonials.map((testimonial) => (
            <div
              key={testimonial.id}
              className="card relative"
            >
              {/* Quote mark */}
              <div className="absolute -top-4 left-6 w-10 h-10 rounded-full bg-brand-gold-100 flex items-center justify-center">
                <svg
                  className="w-5 h-5 text-brand-gold-600"
                  fill="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path d="M14.017 21v-7.391c0-5.704 3.731-9.57 8.983-10.609l.995 2.151c-2.432.917-3.995 3.638-3.995 5.849h4v10h-9.983zm-14.017 0v-7.391c0-5.704 3.748-9.57 9-10.609l.996 2.151c-2.433.917-3.996 3.638-3.996 5.849h3.983v10h-9.983z" />
                </svg>
              </div>

              {/* Content */}
              <div className="pt-6">
                <p className="text-neutral-700 mb-6 leading-relaxed">
                  &ldquo;{testimonial.quote}&rdquo;
                </p>

                {/* Author */}
                <div className="flex items-center gap-4">
                  {/* Avatar placeholder */}
                  <div className="w-12 h-12 rounded-full bg-gradient-to-br from-brand-brown-200 to-brand-gold-200 flex items-center justify-center">
                    <span className="text-lg font-semibold text-brand-brown-700">
                      {testimonial.name.charAt(0)}
                    </span>
                  </div>
                  <div>
                    <p className="font-semibold text-neutral-900">{testimonial.name}</p>
                    <p className="text-sm text-neutral-500">{testimonial.role}</p>
                  </div>
                </div>

                {/* Stats badge */}
                {testimonial.blocksCompleted && (
                  <div className="mt-4 inline-flex items-center gap-1 text-sm text-brand-green-700 bg-brand-green-50 px-3 py-1 rounded-full">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    {testimonial.blocksCompleted} blocks completed
                  </div>
                )}
                {testimonial.blocksFunded && (
                  <div className="mt-4 inline-flex items-center gap-1 text-sm text-brand-gold-700 bg-brand-gold-50 px-3 py-1 rounded-full">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
                    </svg>
                    {testimonial.blocksFunded} blocks funded
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

        {/* Call to action */}
        <div className="mt-16 text-center">
          <p className="text-neutral-600 mb-4">
            Want to share your GCC story?
          </p>
          <a
            href="mailto:stories@globalcommunitycleaners.org"
            className="text-brand-gold-600 font-semibold hover:text-brand-gold-700 transition-colors"
          >
            Contact us →
          </a>
        </div>
      </div>
    </section>
  )
}
