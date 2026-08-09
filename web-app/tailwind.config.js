 /** @type {import('tailwindcss').Config} */
 export default {
   content: [
     './index.html',
     './src/**/*.{vue,js,ts,jsx,tsx}',
   ],
   theme: {
     extend: {
       colors: {
         surface: {
           50: '#f4f6f8',
           100: '#e2e6ea',
           200: '#c4cdd5',
           300: '#9aa7b5',
           400: '#6f7f92',
           500: '#546478',
           600: '#3e4f64',
           700: '#2d3a4d',
           800: '#1a2535',
           900: '#0d1520',
           950: '#05090c',
         },
         accent: {
           DEFAULT: '#60a5fa',
           light: '#93c5fd',
           dark: '#2563eb',
         },
         success: '#34d399',
         warning: '#fbbf24',
         danger: '#f87171',
       },
       fontFamily: {
         sans: [
           'Inter',
           '-apple-system',
           'BlinkMacSystemFont',
           '"Segoe UI"',
           '"PingFang SC"',
           '"Microsoft YaHei"',
           'sans-serif',
         ],
         mono: ['"JetBrains Mono"', '"Fira Code"', 'monospace'],
       },
       animation: {
         'pulse-slow': 'pulse 3s cubic-bezier(0.4, 0, 0.6, 1) infinite',
       },
     },
   },
   plugins: [],
 }
