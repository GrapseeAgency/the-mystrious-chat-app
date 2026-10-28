import test from 'node:test'
import assert from 'node:assert/strict'
import * as fs from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(file, bindings, expression) {
  const text = (await fs.readFile(new URL(`../../${file}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, {mode:'transform'}) + `;return ${expression}`)(...Object.values(bindings))
}
const sweep = await load('src/lib/upload-sweep.ts',
  { mkdir: fs.mkdir, writeFile: fs.writeFile, rename: fs.rename, unlink: fs.unlink, stat: fs.stat, path },
  '({sweepUploads,UPLOAD_SWEEP_GRACE_MS})')

const OLD = new Date('2026-10-01T00:00:00Z')
const FRESH = new Date('2026-10-29T12:00:00Z') // 12h before NOW: inside the 24h grace
const NOW = new Date('2026-10-30T00:00:00Z').getTime()

async function fixture(run) {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'upload-sweep-'))
  const db = new PrismaClient({datasourceUrl:`file:${path.join(dir,'db.sqlite')}`})
  const uploadsDir = path.join(dir, 'uploads')
  try {
    for (const sql of [
      'CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE Message (id TEXT PRIMARY KEY, imagePath TEXT, audioPath TEXT, filePath TEXT)',
      'CREATE TABLE "User" (id TEXT PRIMARY KEY, avatar TEXT, coverImage TEXT)',
      'CREATE TABLE Conversation (id TEXT PRIMARY KEY, photo TEXT)',
      'CREATE TABLE StatusStory (id TEXT PRIMARY KEY, imagePath TEXT)',
    ]) await db.$executeRawUnsafe(sql)
    const upload = async (name, createdAt, bytes = 'file-bytes-' + name) => {
      await db.uploadedFile.create({data:{name, mime:'image/jpeg', bytes:new Uint8Array(Buffer.from(bytes)), size:bytes.length, createdAt}})
      await fs.mkdir(uploadsDir, {recursive:true})
      await fs.writeFile(path.join(uploadsDir, name), bytes)
    }
    await run({db, uploadsDir, upload})
  } finally { await db.$disconnect(); await fs.rm(dir,{recursive:true,force:true}) }
}

test('an unreferenced upload past its grace window is removed from the durable store and the disk cache',()=>fixture(async({db,uploadsDir,upload})=>{
  await upload('orphan-old.jpg', OLD)
  const report = await sweep.sweepUploads(db, uploadsDir, NOW)
  assert.equal(report.removed,1); assert.equal(report.retained,0); assert.equal(report.scanned,1)
  assert.equal(await db.uploadedFile.count(),0)
  await assert.rejects(async()=>fs.stat(path.join(uploadsDir,'orphan-old.jpg')))
}))

test('a fresh unreferenced upload keeps its grace window so a slow send after upload is not punished',()=>fixture(async({db,uploadsDir,upload})=>{
  await upload('orphan-fresh.jpg', FRESH)
  const report = await sweep.sweepUploads(db, uploadsDir, NOW)
  assert.equal(report.removed,0); assert.equal(report.retained,1)
  assert.equal(await db.uploadedFile.count(),1)
}))

test('every durable reference shape protects its upload and a wiped disk cache is republished from the BLOB',()=>fixture(async({db,uploadsDir,upload})=>{
  await upload('msg-image.jpg', OLD)
  await upload('msg-audio.webm', OLD)
  await upload('msg-file.pdf', OLD)
  await upload('avatar.jpg', OLD)
  await upload('cover.jpg', OLD)
  await upload('channel.jpg', OLD)
  await upload('story.jpg', OLD)
  await db.$executeRawUnsafe("INSERT INTO Message (id, imagePath) VALUES ('m1','msg-image.jpg'),('m2',NULL)")
  await db.$executeRawUnsafe("UPDATE Message SET audioPath='msg-audio.webm' WHERE id='m2'")
  await db.$executeRawUnsafe("INSERT INTO Message (id, filePath) VALUES ('m3','msg-file.pdf')")
  await db.$executeRawUnsafe("INSERT INTO \"User\" (id, avatar) VALUES ('u1','/api/uploads/avatar.jpg')")
  await db.$executeRawUnsafe("INSERT INTO \"User\" (id, coverImage) VALUES ('u2','/api/uploads/cover.jpg')")
  await db.$executeRawUnsafe("INSERT INTO Conversation (id, photo) VALUES ('c1','/api/uploads/channel.jpg')")
  await db.$executeRawUnsafe("INSERT INTO StatusStory (id, imagePath) VALUES ('s1','story.jpg')")
  // The sandbox wiped the whole disk cache: every referenced upload must be
  // republished from the durable BLOB.
  await fs.rm(uploadsDir,{recursive:true,force:true})
  const report = await sweep.sweepUploads(db, uploadsDir, NOW)
  assert.equal(report.removed,0); assert.equal(report.republished,7); assert.equal(report.retained,7)
  assert.equal(await db.uploadedFile.count(),7)
  assert.equal(await fs.readFile(path.join(uploadsDir,'msg-audio.webm'),'utf8'),'file-bytes-msg-audio.webm')
  assert.equal(await fs.readFile(path.join(uploadsDir,'avatar.jpg'),'utf8'),'file-bytes-avatar.jpg')
  assert.equal(await fs.readFile(path.join(uploadsDir,'story.jpg'),'utf8'),'file-bytes-story.jpg')
}))

test('a deleted-message reference is conservative: the sweep keeps the file rather than guessing',()=>fixture(async({db,uploadsDir,upload})=>{
  await upload('kept.jpg', OLD)
  // No Message row at all (hard delete) and past grace - this IS an orphan.
  await upload('real-orphan.jpg', OLD)
  await db.$executeRawUnsafe("INSERT INTO Message (id, imagePath) VALUES ('soft-deleted','kept.jpg')")
  const report = await sweep.sweepUploads(db, uploadsDir, NOW)
  assert.equal(report.removed,1); assert.equal(report.retained,1)
  assert.notEqual(await db.uploadedFile.findUnique({where:{name:'kept.jpg'}}),null)
}))

test('the maintenance route refuses requests without the shared secret and sweeps with it',async()=>{
  const dir = await fs.mkdtemp(path.join(tmpdir(),'sweep-route-'))
  const db = new PrismaClient({datasourceUrl:`file:${path.join(dir,'db.sqlite')}`})
  const previousSecret = process.env.CRON_SECRET
  process.env.CRON_SECRET = 'route-test-key'
  try {
    for (const sql of [
      'CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE Message (id TEXT PRIMARY KEY, imagePath TEXT, audioPath TEXT, filePath TEXT)',
      'CREATE TABLE "User" (id TEXT PRIMARY KEY, avatar TEXT, coverImage TEXT)',
      'CREATE TABLE Conversation (id TEXT PRIMARY KEY, photo TEXT)',
      'CREATE TABLE StatusStory (id TEXT PRIMARY KEY, imagePath TEXT)',
    ]) await db.$executeRawUnsafe(sql)
    const post = await load('src/app/api/maintenance/upload-sweep/route.ts',
      { NextResponse: Response, db, UPLOADS_DIR: path.join(dir,'uploads'), sweepUploads: sweep.sweepUploads, console: {error:()=>{}} }, 'POST')
    const denied = await post(new Request('https://sweep.test/api/maintenance/upload-sweep', {method:'POST'}))
    assert.equal(denied.status,401)
    const wrongKey = await post(new Request('https://sweep.test/api/maintenance/upload-sweep', {method:'POST', headers:{'x-pulse-key':'nope'}}))
    assert.equal(wrongKey.status,401)
    const accepted = await post(new Request('https://sweep.test/api/maintenance/upload-sweep', {method:'POST', headers:{'x-pulse-key':'route-test-key'}}))
    assert.equal(accepted.status,200)
    assert.equal((await accepted.json()).scanned,0)
  } finally {
    if (previousSecret === undefined) delete process.env.CRON_SECRET; else process.env.CRON_SECRET = previousSecret
    await db.$disconnect(); await fs.rm(dir,{recursive:true,force:true})
  }
})
