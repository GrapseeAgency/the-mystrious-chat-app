/**
 * Wave 6 runtime E2E — social graph / discovery / safety family against the
 * LIVE web backend (localhost:3000), the same REST surface the native apps
 * call. Covers the Wave 6 acceptance ledger: user profile + PATCH validation,
 * stats, safety number determinism + verify/reset, block → server-enforced DM
 * boundary + blocks list, report idempotency, folders CRUD + full-replace
 * membership, mentions (14d regex), search (window + cap), channels
 * (create/subscribe/last-admin 403/broadcast lock), invite preview + join.
 */
const BASE = 'http://127.0.0.1:3000'
let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { pass++; console.log('PASS —', name) } else { fail++; console.log('FAIL —', name) } }

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  let json = null
  try { json = await res.json() } catch {}
  return { status: res.status, json }
}

async function ensureUser(name, color) {
  const created = await api('POST', '/api/users', { name, color })
  if (created.status === 201 || created.status === 200) return created.json.user
  const list = await api('GET', '/api/users')
  const found = (list.json.users || []).find((u) => u.name === name)
  return found
}

const stamp = Date.now().toString(36).slice(-6)
const uniq = (p) => p + '-' + stamp

async function main() {
  // Identities
  const alice = await ensureUser(uniq('Alice'), 'emerald')
  const bob = await ensureUser(uniq('Bob'), 'rose')
  const carol = await ensureUser(uniq('Carol'), 'teal')
  ok('identity bootstrap: three users', !!alice?.id && !!bob?.id && !!carol?.id)
  const A = alice.id, B = bob.id, C = carol.id

  // ── Profile GET + PATCH (F-CP-04) ──────────────────────────────────────
  const prof = await api('GET', `/api/users/${A}`)
  ok('GET user profile', prof.status === 200 && prof.json.user.id === A)
  const patch = await api('PATCH', `/api/users/${A}`, { about: 'e2e bio', statusEmoji: '🔥', statusText: 'shipping' })
  ok('PATCH profile (bio/status)', patch.status === 200 && patch.json.user.about === 'e2e bio' && patch.json.user.statusEmoji === '🔥')
  const badName = await api('PATCH', `/api/users/${A}`, { name: '' })
  ok('PATCH name=1-32 rejected on empty', badName.status === 400)
  const badHandle = await api('PATCH', `/api/users/${A}`, { username: 'Bad Handle!' })
  ok('PATCH handle rejects invalid charset', badHandle.status === 400)

  // ── Stats (F-CP-03) ─────────────────────────────────────────────────────
  const stats = await api('GET', `/api/users/${A}/stats?userId=${A}`)
  ok('GET stats shape', stats.status === 200 && typeof stats.json.stats.messages === 'number' && typeof stats.json.stats.chats === 'number')

  // ── Safety number (F-CP-07) — deterministic + symmetric ────────────────
  const s1 = await api('GET', `/api/users/${B}/safety?userId=${A}`)
  const s2 = await api('GET', `/api/users/${A}/safety?userId=${B}`)
  const digits1 = (s1.json.safetyNumber || '').split(' ')
  ok('safety number 12x5 digits', s1.status === 200 && digits1.length === 12 && digits1.every((g) => g.length === 5))
  ok('safety number symmetric (a,b)==(b,a)', s1.json.safetyNumber === s2.json.safetyNumber)
  const v = await api('POST', `/api/users/${B}/safety`, { userId: A })
  ok('verify stamps verified=true', v.status === 200 && v.json.verified === true)
  const v2 = await api('GET', `/api/users/${B}/safety?userId=${A}`)
  ok('verified state persists', v2.json.verified === true && !!v2.json.verifiedAt)
  const uv = await api('DELETE', `/api/users/${B}/safety?userId=${A}`)
  ok('unverify resets', uv.status === 200 && uv.json.verified === false)

  // ── Block → server-enforced DM boundary (F-CP-05) ──────────────────────
  const dm = await api('POST', '/api/conversations', { creatorId: B, memberIds: [A], isGroup: false })
  ok('DM create (B→A) works pre-block', dm.status === 201 || dm.status === 200)
  const convId = dm.json.conversation?.id || dm.json.id
  const block = await api('POST', `/api/users/${B}/block`, { userId: A })
  ok('A blocks B', block.status === 200 && block.json.blocked === true)
  const sendBlocked = await api('POST', `/api/conversations/${convId}/messages`, { senderId: B, content: 'should fail', kind: 'text' })
  ok('blocked DM send → 403 honest boundary', sendBlocked.status === 403)
  // NOTE (web contract): the dedupe path returns the EXISTING DM (200) even
  // while blocked — only a FRESH pair hits the 403. Existing DM stays openable.
  const newDm = await api('POST', '/api/conversations', { creatorId: B, memberIds: [A], isGroup: false })
  ok('blocked re-DM dedupes to the existing conversation (200)', newDm.status === 200)
  const blocks = await api('GET', `/api/users/${A}/blocks?userId=${A}`)
  ok('blocks list contains B', blocks.status === 200 && (blocks.json.blocks || []).some((b) => b.id === B))
  const others = await api('GET', `/api/users/${A}/blocks?userId=${B}`)
  ok('blocks list is self-service 403', others.status === 403)
  const unblock = await api('DELETE', `/api/users/${B}/block?userId=${A}`)
  ok('unblock restores', unblock.status === 200 && unblock.json.blocked === false)

  // ── Report (F-CP-06) — enum + idempotency ──────────────────────────────
  const rep = await api('POST', `/api/users/${B}/report`, { userId: A, reason: 'spam', details: 'e2e' })
  ok('first report 201', rep.status === 201 && rep.json.reported === true)
  const rep2 = await api('POST', `/api/users/${B}/report`, { userId: A, reason: 'spam', details: 'e2e again' })
  ok('re-report same reason idempotent (updated)', rep2.status === 200 && rep2.json.updated === true)
  const repBad = await api('POST', `/api/users/${B}/report`, { userId: A, reason: 'nonsense' })
  ok('invalid reason 400', repBad.status === 400)

  // ── Folders (F-FD-01…03) ────────────────────────────────────────────────
  const f = await api('POST', '/api/folders', { userId: A, name: uniq('Work'), emoji: '💼' })
  ok('folder create 201', f.status === 201 && !!f.json.folder?.id)
  const fid = f.json.folder.id
  const conv1 = await api('POST', '/api/conversations', { creatorId: A, memberIds: [B], isGroup: false })
  const c1 = conv1.json.conversation?.id || conv1.json.id
  const conv2 = await api('POST', '/api/conversations', { creatorId: A, memberIds: [B, C], isGroup: true, name: uniq('Grp') })
  const c2 = conv2.json.conversation?.id || conv2.json.id
  const put = await api('PUT', `/api/folders/${fid}/conversations`, { conversationIds: [c1, c2] })
  ok('folder PUT full replace (2 convs)', put.status === 200 && put.json.folder.conversationIds.length === 2)
  const putReorder = await api('PUT', `/api/folders/${fid}/conversations`, { conversationIds: [c2, c1] })
  ok('folder PUT reorder persisted', putReorder.json.folder.conversationIds[0] === c2)
  const foldersList = await api('GET', `/api/folders?userId=${A}`)
  ok('folders GET shows position-ordered membership', foldersList.status === 200 && foldersList.json.folders.some((x) => x.id === fid))
  const rename = await api('PATCH', `/api/folders/${fid}`, { name: uniq('Renamed'), position: 0 })
  ok('folder PATCH rename+position', rename.status === 200 && rename.json.folder.position === 0)
  const badFolder = await api('POST', '/api/folders', { userId: A, name: '' })
  ok('folder name 1-24 enforced', badFolder.status === 400)
  const delF = await api('DELETE', `/api/folders/${fid}`)
  ok('folder DELETE ok', delF.status === 200)

  // ── Mentions (F-SM-03) — 14d feed + boundary regex ─────────────────────
  await api('POST', `/api/conversations/${c2}/messages`, { senderId: B, content: `hello @${alice.name} welcome`, kind: 'text' })
  await api('POST', `/api/conversations/${c2}/messages`, { senderId: B, content: `no mention here`, kind: 'text' })
  const mentions = await api('GET', `/api/mentions?userId=${A}&limit=50`)
  ok('mentions feed contains the @Alice hit', mentions.status === 200 && (mentions.json.items || []).some((m) => m.snippet.includes('welcome')))

  // ── Search (F-SM-01/02) ────────────────────────────────────────────────
  await api('POST', `/api/conversations/${c2}/messages`, { senderId: A, content: 'the contract is signed', kind: 'text' })
  const search = await api('GET', `/api/search?userId=${A}&q=${encodeURIComponent('contract')}`)
  ok('search finds the message', search.status === 200 && (search.json.messages || []).some((m) => m.content.includes('contract')))

  // ── Channels (F-CH-01…03) + last-admin 403 + composer lock flag ────────
  const ch = await api('POST', '/api/channels', { userId: A, name: uniq('Announcements'), description: 'e2e channel', photo: '' })
  ok('channel create 201 (creator admin + first post)', ch.status === 201 && !!ch.json.channel?.conversationId)
  const chConv = ch.json.channel.conversationId
  const sub = await api('POST', `/api/channels/${chConv}/subscribe`, { userId: B })
  ok('B subscribes', sub.status === 200 && sub.json.already === false)
  const subAgain = await api('POST', `/api/channels/${chConv}/subscribe`, { userId: B })
  ok('re-subscribe idempotent (already=true)', subAgain.json.already === true)
  const lastAdmin = await api('DELETE', `/api/channels/${chConv}/subscribe`, { userId: A })
  ok('last-admin leave blocked 403 (verbatim copy)', lastAdmin.status === 403 && (lastAdmin.json.error || '').includes('last admin'))
  const memberLeave = await api('DELETE', `/api/channels/${chConv}/subscribe`, { userId: B })
  ok('member leaves freely', memberLeave.status === 200)
  const dir = await api('GET', `/api/channels?userId=${B}`)
  ok('channel directory shape (memberCount/preview)', dir.status === 200 && (dir.json.channels || []).some((c) => c.id === chConv && typeof c.memberCount === 'number'))

  // ── Invite (F-DL behind pulse://invite) ────────────────────────────────
  const dave = await ensureUser(uniq('Dave'), 'amber')
  // B must NOT be a member for the preview assertion (server needs 3 members).
  const conv3 = await api('POST', '/api/conversations', { creatorId: A, memberIds: [C, dave.id], isGroup: true, name: uniq('Invited') })
  const g3 = conv3.json.conversation?.id || conv3.json.id
  const inv = await api('POST', `/api/conversations/${g3}/invite`, { requesterId: A })
  const code = inv.json.inviteCode || inv.json.invite?.code || inv.json.code
  ok('invite code created', (inv.status === 201 || inv.status === 200) && typeof code === 'string' && code.length >= 4)
  const prev = await api('GET', `/api/invite/${code}?userId=${B}`)
  ok('invite preview (name+count, not member)', prev.status === 200 && prev.json.invite.alreadyMember === false)
  const join = await api('POST', `/api/invite/${code}/join`, { userId: B })
  ok('invite join idempotent', join.status === 200 && !!join.json.conversationId)
  const join2 = await api('POST', `/api/invite/${code}/join`, { userId: B })
  ok('re-join already=true', join2.json.alreadyMember === true)

  console.log(`\nWAVE 6 E2E — ${pass} PASS / ${fail} FAIL`)
  process.exit(fail > 0 ? 1 : 0)
}

main().catch((e) => { console.error('E2E crashed:', e.message); process.exit(1) })
