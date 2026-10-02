// Pulse Chat - UI theme attribute mirror.
// Writes the active design language onto document.body as data-ui so
// EVERY surface inherits the --ui-* tokens: tabs inside .ui-root,
// full-screen overlays, hash sub-pages and body-portaled sheets/menus
// (portals escape the shell root in the DOM, so they never saw the
// language before this mirror - the "two themes in one app" bug).
// The store is the single source of truth; this component just keeps
// the body attribute in lockstep with it.
'use client'

import { useEffect } from 'react'
import { useUiThemeStore } from '@/lib/ui-theme'

export function UiThemeAttr() {
  const theme = useUiThemeStore((s) => s.theme)

  useEffect(() => {
    document.body.dataset.ui = `ui-${theme}`
  }, [theme])

  return null
}
