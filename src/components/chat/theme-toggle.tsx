// ─────────────────────────────────────────────────────────────
// Pulse Chat — light/dark toggle (next-themes).
// ─────────────────────────────────────────────────────────────
'use client'

import { useTheme } from 'next-themes'
import { Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useMounted } from '@/hooks/use-mounted'

export function ThemeToggleButton() {
  const { resolvedTheme, setTheme } = useTheme()
  const mounted = useMounted()

  const isDark = mounted && resolvedTheme === 'dark'

  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
      className="size-10 rounded-full text-zinc-500 hover:bg-zinc-100 hover:text-zinc-700 active:scale-95 dark:hover:bg-zinc-800 dark:hover:text-zinc-300"
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
    >
      {isDark ? <Sun className="size-[18px]" /> : <Moon className="size-[18px]" />}
    </Button>
  )
}
