import type { Config } from 'tailwindcss'

const config: Config = {
    darkMode: ['class'],
    content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
  	extend: {
  		colors: {
  			brand: {
  				green: {
  					'50': '#f5f9f7',
  					'100': '#e7f1eb',
  					'200': '#cbe0d3',
  					'300': '#a1c8b0',
  					'400': '#67a57e',
  					'500': '#16763a',
  					'600': '#126431',
  					'700': '#0f5228',
  					'800': '#0b3b1d',
  					'900': '#072914'
  				},
  				blue: {
  					'50': '#f5f8fa',
  					'100': '#e7eef4',
  					'200': '#cbdae7',
  					'300': '#a1bdd3',
  					'400': '#6694b8',
  					'500': '#155b92',
  					'600': '#114d7c',
  					'700': '#0e3f66',
  					'800': '#0a2d49',
  					'900': '#071f33'
  				},
  				sky: {
  					'50': '#f6fbfd',
  					'100': '#eaf5fa',
  					'200': '#d1eaf4',
  					'300': '#acd9eb',
  					'400': '#78c1df',
  					'500': '#30a0ce',
  					'600': '#2888af',
  					'700': '#217090',
  					'800': '#185067',
  					'900': '#103848'
  				}
  			},
  			background: 'hsl(var(--background))',
  			foreground: 'hsl(var(--foreground))',
  			card: {
  				DEFAULT: 'hsl(var(--card))',
  				foreground: 'hsl(var(--card-foreground))'
  			},
  			popover: {
  				DEFAULT: 'hsl(var(--popover))',
  				foreground: 'hsl(var(--popover-foreground))'
  			},
  			primary: {
  				DEFAULT: 'hsl(var(--primary))',
  				foreground: 'hsl(var(--primary-foreground))'
  			},
  			secondary: {
  				DEFAULT: 'hsl(var(--secondary))',
  				foreground: 'hsl(var(--secondary-foreground))'
  			},
  			muted: {
  				DEFAULT: 'hsl(var(--muted))',
  				foreground: 'hsl(var(--muted-foreground))'
  			},
  			accent: {
  				DEFAULT: 'hsl(var(--accent))',
  				foreground: 'hsl(var(--accent-foreground))'
  			},
  			destructive: {
  				DEFAULT: 'hsl(var(--destructive))',
  				foreground: 'hsl(var(--destructive-foreground))'
  			},
  			border: 'hsl(var(--border))',
  			input: 'hsl(var(--input))',
  			ring: 'hsl(var(--ring))',
  			chart: {
  				'1': 'hsl(var(--chart-1))',
  				'2': 'hsl(var(--chart-2))',
  				'3': 'hsl(var(--chart-3))',
  				'4': 'hsl(var(--chart-4))',
  				'5': 'hsl(var(--chart-5))'
  			}
  		},
  		fontFamily: {
  			sans: [
  				'var(--font-inter)',
  				'system-ui',
  				'sans-serif'
  			],
  			display: [
  				'var(--font-display)',
  				'var(--font-inter)',
  				'system-ui',
  				'sans-serif'
  			]
  		},
  		animation: {
  			'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
  			'bounce-slow': 'bounce 2s infinite'
  		},
  		borderRadius: {
  			lg: 'var(--radius)',
  			md: 'calc(var(--radius) - 2px)',
  			sm: 'calc(var(--radius) - 4px)'
  		}
  	}
  },
  plugins: [require("tailwindcss-animate")],
}

export default config
