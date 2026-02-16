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
  				brown: {
  					'50': '#fdf8f6',
  					'100': '#f2e8e5',
  					'200': '#eaddd7',
  					'300': '#e0cec7',
  					'400': '#d2bab0',
  					'500': '#bfa094',
  					'600': '#a18072',
  					'700': '#8b6e5a',
  					'800': '#6b5344',
  					'900': '#4a3931'
  				},
  				gold: {
  					'50': '#fefce8',
  					'100': '#fef9c3',
  					'200': '#fef08a',
  					'300': '#fde047',
  					'400': '#facc15',
  					'500': '#d4a017',
  					'600': '#b8860b',
  					'700': '#8b6914',
  					'800': '#713f12',
  					'900': '#5c3810'
  				},
  				green: {
  					'50': '#f0fdf4',
  					'100': '#dcfce7',
  					'200': '#bbf7d0',
  					'300': '#86efac',
  					'400': '#4ade80',
  					'500': '#22c55e',
  					'600': '#16a34a',
  					'700': '#166534',
  					'800': '#14532d',
  					'900': '#0d3320'
  				},
  				sky: {
  					'50': '#f0f9ff',
  					'100': '#e0f2fe',
  					'200': '#bae6fd',
  					'300': '#7dd3fc',
  					'400': '#38bdf8',
  					'500': '#0ea5e9',
  					'600': '#0284c7',
  					'700': '#0369a1',
  					'800': '#075985',
  					'900': '#0c4a6e'
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
  				'var(--font-cal-sans)',
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
