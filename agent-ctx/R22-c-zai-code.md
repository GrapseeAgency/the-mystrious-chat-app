# R22-c — Pulse premium UI: FX layer, WebGL hero, glass primitives

Agent: Z.ai Code (premium UI overhaul crew)
Status: ✅ COMPLETE — gates green, contracts documented.

## Files (ownership respected — nothing else touched)

| File | Kind | Contents |
|---|---|---|
| `src/components/fx/particle-layer.tsx` | NEW | `ParticleLayer` (memo, no props) — global full-screen particle canvas |
| `src/components/fx/webgl-glow.tsx` | NEW | `WebglGlow` ({className?, intensity?=1}) — raw WebGL1 liquid-gradient hero + CSS fallback |
| `src/components/ui/glass-card.tsx` | NEW | `GlassCard` + `glassSurface` class-string export |
| `src/components/chat/app-root.tsx` | EDIT (1 mount) | `<ParticleLayer />` inside `<Providers>`, sibling above `<BootGate />` |
| `src/components/chat/spotlight.tsx` | EDIT (polish) | glassSurface panel, spring.soft y-8→0 entrance, row stagger 25ms + whileTap 0.97 |
| `src/components/chat/slash-palette.tsx` | EDIT (polish) | glassSurface panel, spring.snappy, row stagger 25ms + whileTap 0.97 |

**Path discrepancy note:** the task named `src/app/app-root.tsx` — that file doesn't exist. The real application root (read it first, as instructed) is `src/components/chat/app-root.tsx` (mounted by `page.tsx`). The single ParticleLayer mount lives there; nothing else in the file changed.

## Contracts for the lead's browser pass / other crews

1. **ParticleLayer** — already mounted GLOBALLY at the app root (do not mount twice).
   Fire from anywhere: `import { fireParticles } from '@/lib/motion'` →
   `fireParticles({ kind?: 'confetti'|'hearts'|'stars'|'burst', x?: 0..1, y?: 0..1, count? })`
   (defaults: kind 'confetti', pos 0.5/0.6, count 80; hard cap 400; reduced-motion skips).
   Canvas: fixed inset-0 z-[95], pointer-events-none — above screens (z-60/70), below toasts (z-100).
2. **WebglGlow** — props `{ className?, intensity? }`. Fills its parent (canvas `h-full w-full`);
   wrap in a sized/rounded/overflow-hidden container. Pauses offscreen/hidden; static frame under
   prefers-reduced-motion; falls back to a matching CSS gradient if WebGL is missing/lost.
3. **glassSurface / GlassCard** — one-import liquid-glass material; tailwind-merge-friendly
   (rounded-*/shadow-* can be overridden by later classes); `glow="emerald"` adds a top-edge bloom.
4. **Behavior contracts preserved**: spotlight `onOpenConversation(conversationId, anchorMs?, jumpMessageId)`
   message-jump untouched; slash-palette keyboard capture + `WHITEBOARD_OPEN_EVENT` dispatch on
   /whiteboard activate (R21-c contract) + onSelect flow all verbatim.

## Gates

- `npx tsc --noEmit 2>&1 | grep "^src/"` → **0 lines**
- `bun run lint` → **clean (0 problems)**
- `GET / → 200` fresh compile; dev.log error-free for my files (the historical
  `db.statusStory.create` 500 in stories route is R21-d's, pre-dates this task)
- No `bun run build` · :3000 untouched · no agent-browser (lead verifies centrally)
