// ─────────────────────────────────────────────────────────────
// Pulse — location share (WhatsApp/Telegram-grade).
// navigator.geolocation → confirm sheet (coords + label) →
// REAL message: kind:'location', payload {lat,lng,label}.
// Bubbles render a stylized CSS map card with a pulsing pin and
// an "Open in Google Maps" deep link. No static map images —
// the card is pure CSS so it works offline and never calls
// third-party tile servers.
// ─────────────────────────────────────────────────────────────
'use client'

import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, ExternalLink, LoaderCircle, LocateFixed, MapPin, X } from 'lucide-react'
import { haptic } from '@/lib/pulse-settings'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer'
import { Input } from '@/components/ui/input'

export interface LocationPayload {
  lat: number
  lng: number
  label: string
}

type GeoStatus = 'locating' | 'ready' | 'error'

const DEFAULT_LABEL = 'Current location'

/** Clamp a normalized 0..100 percentage into a visually safe band. */
function clampPct(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

/**
 * Stylized map card for kind:'location' bubbles. The pin's position is
 * derived from the real coordinates (equirectangular projection onto the
 * card) so different places land in visibly different spots.
 */
export function LocationBubble({
  lat,
  lng,
  label,
  mine,
}: {
  lat: number
  lng: number
  label: string
  mine: boolean
}) {
  const xPct = clampPct(((lng + 180) / 360) * 100, 12, 88)
  const yPct = clampPct(((90 - lat) / 180) * 100, 14, 82)
  const mapsUrl = `https://maps.google.com/?q=${lat},${lng}`
  const coordText = `${Math.abs(lat).toFixed(4)}° ${lat >= 0 ? 'N' : 'S'}, ${Math.abs(lng).toFixed(4)}° ${lng >= 0 ? 'E' : 'W'}`

  return (
    <div
      className={cn(
        'relative w-[228px] overflow-hidden rounded-xl',
        mine ? 'bg-emerald-600' : 'bg-emerald-100 dark:bg-zinc-800',
      )}
      style={{ height: 132 }}
    >
      {/* CSS map: dot grid + graticule lines + two "streets" */}
      <div
        aria-hidden
        className={cn('absolute inset-0', mine ? 'opacity-25' : 'opacity-40 dark:opacity-25')}
        style={{
          backgroundImage:
            'radial-gradient(circle, rgba(6,78,59,0.55) 1px, transparent 1px), repeating-linear-gradient(0deg, rgba(6,78,59,0.28) 0 1px, transparent 1px 22px), repeating-linear-gradient(90deg, rgba(6,78,59,0.28) 0 1px, transparent 1px 22px)',
          backgroundSize: '18px 18px, 100% 100%, 100% 100%',
        }}
      />
      <div
        aria-hidden
        className={cn('absolute left-[-30%] top-[38%] h-[7px] w-[170%] -rotate-6 rounded-full', mine ? 'bg-white/20' : 'bg-emerald-200/80 dark:bg-zinc-700')}
      />
      <div
        aria-hidden
        className={cn('absolute left-[52%] top-[-20%] h-[150%] w-[6px] rotate-12 rounded-full', mine ? 'bg-white/15' : 'bg-emerald-200/70 dark:bg-zinc-700/90')}
      />

      {/* pulsing pin at the projected coordinate */}
      <span
        aria-hidden
        className="absolute z-10 -translate-x-1/2 -translate-y-full"
        style={{ left: `${xPct}%`, top: `${yPct}%` }}
      >
        <span className="relative flex items-end justify-center">
          <span
            className={cn(
              'absolute bottom-0 size-4 animate-ping rounded-full opacity-60',
              mine ? 'bg-white/70' : 'bg-emerald-500/70',
            )}
            style={{ animationDuration: '1.4s' }}
          />
          <span
            className={cn(
              'relative flex size-6 items-center justify-center rounded-full shadow-md',
              mine ? 'bg-white text-emerald-600' : 'bg-emerald-500 text-white',
            )}
          >
            <MapPin className="size-3.5" aria-hidden />
          </span>
        </span>
      </span>

      {/* footer: label + coords + open-in-maps */}
      <div
        className={cn(
          'absolute inset-x-0 bottom-0 z-10 flex items-center gap-1.5 px-2 py-1.5',
          mine ? 'bg-black/25' : 'bg-white/85 dark:bg-zinc-900/85',
        )}
      >
        <div className="min-w-0 flex-1">
          <p className={cn('truncate text-[11.5px] font-bold leading-tight', mine ? 'text-white' : 'text-zinc-800 dark:text-zinc-100')}>
            {label}
          </p>
          <p className={cn('truncate text-[9px] font-medium tabular-nums leading-tight', mine ? 'text-white/75' : 'text-zinc-500 dark:text-zinc-400')}>
            {coordText}
          </p>
        </div>
        <a
          href={mapsUrl}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => e.stopPropagation()}
          aria-label={`Open ${label} in Google Maps`}
          className={cn(
            'flex size-6 shrink-0 items-center justify-center rounded-full outline-none transition-transform active:scale-90',
            mine ? 'bg-white/20 text-white hover:bg-white/30' : 'bg-emerald-500 text-white hover:bg-emerald-500/90',
          )}
        >
          <ExternalLink className="size-3" aria-hidden />
        </a>
      </div>
    </div>
  )
}

/** Safe-parse a location payload from a message's payload JSON. */
export function parseLocationPayload(payload: string | null): LocationPayload | null {
  if (!payload) return null
  try {
    const raw: unknown = JSON.parse(payload)
    if (typeof raw !== 'object' || raw === null) return null
    const p = raw as Record<string, unknown>
    const lat = p.lat
    const lng = p.lng
    if (typeof lat !== 'number' || typeof lng !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lng)) {
      return null
    }
    const label = typeof p.label === 'string' ? p.label : DEFAULT_LABEL
    return { lat, lng, label }
  } catch {
    return null
  }
}

export function LocationShareSheet({
  open,
  onClose,
  onConfirm,
}: {
  open: boolean
  onClose: () => void
  onConfirm: (payload: LocationPayload) => void
}) {
  const [status, setStatus] = useState<GeoStatus>('locating')
  const [errorMsg, setErrorMsg] = useState('')
  const [coords, setCoords] = useState<{ lat: number; lng: number } | null>(null)
  const [label, setLabel] = useState(DEFAULT_LABEL)
  const [sending, setSending] = useState(false)
  const watchTokenRef = useRef(0)

  useEffect(() => {
    if (!open) return
    // deferred one tick so opening the sheet never cascades a sync re-render
    const kick = setTimeout(() => {
      const token = ++watchTokenRef.current
      setStatus('locating')
      setErrorMsg('')
      setCoords(null)
      setLabel(DEFAULT_LABEL)

      if (typeof navigator === 'undefined' || !navigator.geolocation) {
        setStatus('error')
        setErrorMsg('This browser cannot access your location.')
        return
      }
      navigator.geolocation.getCurrentPosition(
        (position) => {
          if (watchTokenRef.current !== token) return
          setCoords({ lat: position.coords.latitude, lng: position.coords.longitude })
          setStatus('ready')
        },
        (error) => {
          if (watchTokenRef.current !== token) return
          setStatus('error')
          setErrorMsg(
            error.code === error.PERMISSION_DENIED
              ? 'Location access was denied — enable it in your browser settings to share a pin.'
              : 'Could not determine your location — check connection and try again.',
          )
        },
        { enableHighAccuracy: true, timeout: 9000, maximumAge: 30_000 },
      )
    }, 0)
    return () => clearTimeout(kick)
  }, [open])

  const confirm = () => {
    if (!coords || sending) return
    setSending(true)
    haptic(12)
    onConfirm({ lat: coords.lat, lng: coords.lng, label: label.trim().slice(0, 80) || DEFAULT_LABEL })
    setSending(false)
  }

  return (
    <Drawer
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <DrawerContent className="mx-auto max-w-[420px] rounded-t-3xl bg-white px-4 pb-[max(0.9rem,env(safe-area-inset-bottom))] pt-2 dark:bg-zinc-900">
        <DrawerTitle className="sr-only">Share location</DrawerTitle>
        <DrawerDescription className="sr-only">Attach your current position as a map card</DrawerDescription>

        <div className="pb-2">
          {status === 'locating' ? (
            <div className="flex flex-col items-center gap-3 py-10" role="status" aria-label="Finding your location">
              <LoaderCircle className="size-7 animate-spin text-emerald-500" aria-hidden />
              <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">Finding you…</p>
            </div>
          ) : status === 'error' ? (
            <div className="flex flex-col items-center gap-3 py-8 text-center">
              <span className="flex size-12 items-center justify-center rounded-2xl bg-rose-500/10 text-rose-500">
                <AlertTriangle className="size-6" aria-hidden />
              </span>
              <p className="max-w-[260px] text-[13px] font-medium leading-relaxed text-zinc-600 dark:text-zinc-300">
                {errorMsg}
              </p>
              <div className="mt-1 flex w-full gap-2">
                <Button
                  variant="outline"
                  onClick={onClose}
                  className="h-10 flex-1 rounded-2xl text-sm font-medium"
                >
                  <X className="mr-1 size-4" aria-hidden />
                  Close
                </Button>
                <Button
                  onClick={() => {
                    // re-run the locate effect by nudging state through close/open cycle
                    setStatus('locating')
                    setErrorMsg('')
                    if (navigator.geolocation) {
                      const token = ++watchTokenRef.current
                      navigator.geolocation.getCurrentPosition(
                        (position) => {
                          if (watchTokenRef.current !== token) return
                          setCoords({ lat: position.coords.latitude, lng: position.coords.longitude })
                          setStatus('ready')
                        },
                        (error) => {
                          if (watchTokenRef.current !== token) return
                          setStatus('error')
                          setErrorMsg(
                            error.code === error.PERMISSION_DENIED
                              ? 'Location access was denied — enable it in your browser settings to share a pin.'
                              : 'Could not determine your location — check connection and try again.',
                          )
                        },
                        { enableHighAccuracy: true, timeout: 9000, maximumAge: 30_000 },
                      )
                    }
                  }}
                  className="h-10 flex-1 gap-1.5 rounded-2xl bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90"
                >
                  <LocateFixed className="size-4" aria-hidden />
                  Retry
                </Button>
              </div>
            </div>
          ) : coords ? (
            <>
              <p className="pb-2 pt-1 text-center text-xs font-medium text-zinc-400 dark:text-zinc-500">
                Share this pin in the chat
              </p>
              <div className="flex justify-center">
                <LocationBubble lat={coords.lat} lng={coords.lng} label={label.trim() || DEFAULT_LABEL} mine />
              </div>
              <div className="mt-3">
                <Input
                  value={label}
                  maxLength={80}
                  onChange={(e) => setLabel(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      confirm()
                    }
                  }}
                  placeholder="Label this place…"
                  aria-label="Location label"
                  className="h-11 rounded-2xl border-zinc-200 bg-zinc-100 text-sm focus-visible:ring-emerald-500/60 dark:border-zinc-700 dark:bg-zinc-800"
                />
              </div>
              <div className="mt-3 flex gap-2">
                <Button variant="outline" onClick={onClose} className="h-11 flex-1 rounded-2xl text-sm font-medium">
                  Cancel
                </Button>
                <Button
                  onClick={confirm}
                  className="h-11 flex-[1.6] gap-1.5 rounded-2xl bg-emerald-500 text-sm font-bold text-white shadow-md shadow-emerald-600/25 hover:bg-emerald-500/90"
                >
                  <MapPin className="size-4" aria-hidden />
                  Send pin
                </Button>
              </div>
            </>
          ) : null}
        </div>
      </DrawerContent>
    </Drawer>
  )
}
