// AGV 后端 V2.1 实时通道验收脚本（真实 HTTP：经网关 8080 / 可选经 vite 5173 代理）
//
// 覆盖：
//   1) GET /agv/live 结构 + ETag；If-None-Match 未变化 → HTTP 304 无 body；未知 etag → 200
//   2) GET /map/current 读缓存 + If-None-Match / ?version= → 304，旧响应结构不变
//   3) 空闲 30s：/agv/live 轮询 → 基本全 304（无高频 200/无数据变化）
//   4) 运行中 30s：SSE 帧数 ≈30（≤1 帧/s、无变化不推帧）、经 8080 网关可连续收帧
//   5) 响应体积：/agv/live 单帧 vs 旧 /agv/list + /map/current
//   6) 写后立刻读一致性（R-V2-08）：batch/path/speed/report/start/stop/charge/clear/saveMap/cancel
//   7) （可选）经 vite 5173 代理收 SSE 帧
//
// 用法：node scripts/verify-realtime.mjs
// 结果明细写入 .verify/api-verify-realtime-result.json
import { mkdirSync, writeFileSync } from 'node:fs'

const BASE = process.env.AGV_API_BASE || 'http://localhost:8080/api'
const VITE = process.env.AGV_VITE_BASE || 'http://localhost:5173/api'
const OUT = './.verify'
mkdirSync(OUT, { recursive: true })

const results = []
const samples = {}
let failures = 0

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function assert(cond, detail) {
  if (!cond) {
    failures += 1
    console.log(`  ✗ ${detail}`)
    return false
  }
  console.log(`  ✓ ${detail}`)
  return true
}

async function http(name, path, { method = 'GET', body, headers, base = BASE, timeout = 20000 } = {}) {
  const started = Date.now()
  try {
    const res = await fetch(base + path, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json', ...headers } : headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeout),
    })
    const text = await res.text()
    let json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      json = null
    }
    const bytes = Buffer.byteLength(text, 'utf8')
    results.push({
      name,
      path,
      method,
      status: res.status,
      ok: res.status >= 200 && res.status < 400,
      bytes,
      ms: Date.now() - started,
    })
    return {
      name,
      path,
      method,
      status: res.status,
      headers: res.headers,
      text,
      json,
      bytes,
      ms: Date.now() - started,
    }
  } catch (e) {
    failures += 1
    console.log(`  [请求异常] ${name} ${path}: ${e.message || e}`)
    return { name, path, method, status: 0, error: String(e.message || e), bytes: 0, ms: Date.now() - started }
  }
}

/** 采集 SSE 帧：解析 event/data，统计 state 帧与心跳 */
async function collectSse(name, url, durationMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), durationMs)
  const stat = { name, url, status: null, contentType: null, frames: [], heartbeats: 0, error: null, durationMs: 0 }
  const started = Date.now()
  try {
    const res = await fetch(url, {
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    })
    stat.status = res.status
    stat.contentType = res.headers.get('content-type')
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (!block.trim()) continue
        const lines = block.split('\n')
        const eventLine = lines.find((l) => l.startsWith('event:'))
        const dataLines = lines.filter((l) => l.startsWith('data:'))
        if (!eventLine) {
          // 心跳是注释行（": heartbeat"）
          if (lines.some((l) => l.trim().startsWith(':'))) stat.heartbeats += 1
          continue
        }
        const event = eventLine.slice('event:'.length).trim()
        const data = dataLines.map((l) => l.slice('data:'.length).trimStart()).join('\n')
        let parsed = null
        try {
          parsed = JSON.parse(data)
        } catch {
          // 忽略不可解析帧
        }
        stat.frames.push({ event, data, parsed, at: Date.now(), bytes: Buffer.byteLength(data, 'utf8') })
      }
    }
  } catch (e) {
    const msg = String(e.message || e)
    if (e.name !== 'AbortError' && !msg.includes('abort')) stat.error = msg
  } finally {
    clearTimeout(timer)
  }
  stat.durationMs = Date.now() - started
  return stat
}

const liveCars = async () => (await http('实时快照', '/agv/live')).json?.cars ?? []
const listCars = async () => (await http('车辆列表', '/agv/list', { method: 'POST' })).json?.data ?? []
const findCar = (cars, code) => (cars ?? []).find((c) => c.agvCode === code)
const cancelTasksOf = async (agvCode) => {
  const page = await http('查询任务', '/task/page', { method: 'POST', body: { page: 1, pageSize: 100, agvCode } })
  for (const task of page.json?.data?.records ?? []) {
    if (['processing', 'paused', 'waiting'].includes(task.status)) {
      await http(`取消任务${task.taskId}`, `/task/cancel/${task.taskId}`)
    }
  }
}
const waitUntilIdle = async (timeoutMs = 30000) => {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const cars = await liveCars()
    if (cars.length > 0 && cars.every((c) => c.status === 'idle' && c.taskId == null)) return true
    await sleep(1000)
  }
  return false
}
/** 等待 /agv/live 的 etag 稳定（连续两次一致），避免把上一动作触发的状态变化算进空闲测试 */
const waitStableLive = async (timeoutMs = 10000) => {
  let last = (await http('等稳定-快照', '/agv/live')).json?.etag
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeoutMs) {
    await sleep(1200)
    const current = (await http('等稳定-快照', '/agv/live')).json?.etag
    if (current && current === last) return current
    last = current
  }
  return last
}

console.log(`\n===== AGV 后端 V2.1 实时通道验收 ${BASE} =====`)

// ---------------------------------------------------------------- 0. /agv/live 结构与 ETag
console.log('\n[0] GET /agv/live 结构 / ETag / 304')
const live0 = await http('实时快照', '/agv/live')
const live = live0.json
assert(live0.status === 200 && live && typeof live.timestamp === 'string' && typeof live.etag === 'string', `GET /agv/live 返回 200 + {timestamp, etag}（body ${live0.bytes}B）`)
assert(live?.map && typeof live.map.mapId === 'number' && typeof live.map.version === 'number' && typeof live.map.width === 'number' && typeof live.map.height === 'number', `map 元信息齐全（mapId=${live?.map?.mapId} v${live?.map?.version} ${live?.map?.width}x${live?.map?.height}）`)
assert(Array.isArray(live?.cars) && live.cars.length > 0, `cars 为数组（${live?.cars?.length} 台）`)
assert(Array.isArray(live?.paths), `paths 为数组（${live?.paths?.length} 个占用路径点）`)
assert((live?.cars ?? []).every((c) => typeof c.agvCode === 'string' && typeof c.x === 'number' && typeof c.y === 'number' && typeof c.status === 'string'), 'cars 含 agvCode/x/y/status')
assert((live?.cars ?? []).every((c) => !c.plannedPath || c.plannedPath.every((p) => Object.keys(p).sort().join(',') === 'x,y')), 'plannedPath 精简为 x/y（不含 parent/g/h/cost）')
const liveEtag = live0.headers.get('etag')
assert(!!liveEtag, `响应头带 ETag=${liveEtag}`)
samples.live = { etag: live?.etag, headerEtag: liveEtag, bytes: live0.bytes, cars: live?.cars?.length, paths: live?.paths?.length }

const live304 = await http('实时快照-If-None-Match', '/agv/live', { headers: { 'If-None-Match': liveEtag } })
assert(live304.status === 304 && live304.bytes === 0, `If-None-Match 未变化 → HTTP 304 无 body（status=${live304.status}, bytes=${live304.bytes}）`)
const liveUnknown = await http('实时快照-未知ETag', '/agv/live', { headers: { 'If-None-Match': '"unknown-etag-xxx"' } })
assert(liveUnknown.status === 200 && liveUnknown.bytes > 0, `未知 ETag → HTTP 200 全量（${liveUnknown.bytes}B）`)

// ---------------------------------------------------------------- 1. /map/current 读缓存 + 304
console.log('\n[1] GET /map/current 读缓存 + ETag/version 304（旧结构不变）')
const map0 = await http('当前地图', '/map/current')
assert(map0.status === 200 && map0.json?.code === 200 && map0.json?.data?.mapId != null, `GET /map/current 返回 code=200 + data（${map0.bytes}B）`)
assert(typeof map0.json?.data?.obstacles?.length === 'number' && Array.isArray(map0.json?.data?.stations), '地图响应结构不变（obstacles/stations/paths）')
const mapEtag = map0.headers.get('etag')
const map304a = await http('当前地图-If-None-Match', '/map/current', { headers: { 'If-None-Match': mapEtag } })
assert(map304a.status === 304 && map304a.bytes === 0, `地图 If-None-Match 命中 → HTTP 304 无 body（status=${map304a.status}）`)
const mapVersion = map0.json?.data?.version
const map304b = await http('当前地图-?version', `/map/current?version=${mapVersion}`)
assert(map304b.status === 304 && map304b.bytes === 0, `地图 ?version=${mapVersion} 命中 → HTTP 304 无 body（status=${map304b.status}）`)
const map200b = await http('当前地图-旧版本号', '/map/current?version=0')
assert(map200b.status === 200 && map200b.bytes > 0, '地图未知 version → 200 全量')
samples.map = { etag: mapEtag, bytes: map0.bytes, version: mapVersion, obstacles: map0.json?.data?.obstacles?.length, stations: map0.json?.data?.stations?.length }

// ---------------------------------------------------------------- 2. 空闲 30s 轮询
console.log('\n[2] 空闲 30s：/agv/live 轮询几乎全 304（无高频数据变化）')
const idleReady = await waitUntilIdle(30000)
assert(idleReady, '开始空闲测试前车队全部 idle 且无任务')
await waitStableLive(10000)
let pollEtag = (await http('空闲-初始快照', '/agv/live')).json?.etag
let idle200 = 0
let idle304 = 0
let idleOther = 0
let idle200Bytes = 0
let idle304Bytes = 0
let idleChanges = []
for (let i = 0; i < 30; i += 1) {
  await sleep(1000)
  const r = await http('空闲轮询', '/agv/live', { headers: { 'If-None-Match': pollEtag } })
  if (r.status === 304) {
    idle304 += 1
    idle304Bytes += r.bytes
  } else if (r.status === 200) {
    idle200 += 1
    idle200Bytes += r.bytes
    idleChanges.push({ at: i + 1, etag: r.json?.etag, cars: (r.json?.cars ?? []).map((c) => `${c.agvCode}:${c.status}@${c.x},${c.y}`).join('|') })
    pollEtag = r.json?.etag
  } else {
    idleOther += 1
  }
}
assert(idleOther === 0, `空闲 30s 无非 200/304 响应（异常 ${idleOther} 次）`)
assert(idle304 >= 27 && idle200 <= 3, `空闲 30s：304=${idle304} 次、200=${idle200} 次（期望 304≥27、200≤3）`)
assert(idle304Bytes === 0, `304 响应体积为 0（合计 ${idle304Bytes}B）`)
samples.idle = { polls: 30, count304: idle304, count200: idle200, idle200Bytes, changes: idleChanges }

// ---------------------------------------------------------------- 3. 运行中 30s SSE
console.log('\n[3] 运行中 30s：SSE 帧数 ≈30（≤1 帧/s、经 8080 网关）')
const carsForTask = await liveCars()
const taskCar = carsForTask.find((c) => c.status === 'idle' && c.taskId == null)
assert(!!taskCar, `找到空闲车用于运行测试（${taskCar?.agvCode}）`)
const taskCode = `RT-${Date.now()}`
const pathRes = await http('创建运行任务', '/agv/path', {
  method: 'POST',
  body: {
    agvCode: taskCar.agvCode,
    taskCode,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S6', x: 37, y: 27 },
    currentPoint: { x: taskCar.x, y: taskCar.y },
  },
})
assert(pathRes.json?.code === 200 && Array.isArray(pathRes.json?.data), `POST /agv/path 成功（${pathRes.json?.data?.length} 个节点）`)
// 写后立刻读：taskId/status 必须立即变化
const liveAfterPath = await liveCars()
const carAfterPath = findCar(liveAfterPath, taskCar.agvCode)
assert(carAfterPath?.taskId != null && carAfterPath?.status === 'running', `R-V2-08 写后立刻读：taskId=${carAfterPath?.taskId} status=${carAfterPath?.status}`)
const listAfterPath = findCar(await listCars(), taskCar.agvCode)
assert(listAfterPath?.taskId === carAfterPath?.taskId, '/agv/list 写后立刻读同样看到 taskId')

// SSE 连接数：注意可能有其它页面（如前端 dev 页面）同时连着 SSE，因此用“相对增量”断言
const connBaseline = (await http('SSE连接数-基线', '/agv/stream/status')).json?.data?.connections ?? 0
const ssePromise = collectSse('SSE-8080-30s', BASE + '/agv/stream', 30000)
await sleep(2500)
const streamStatus = await http('SSE连接数', '/agv/stream/status')
const connectionsDuring = streamStatus.json?.data?.connections
assert(typeof connectionsDuring === 'number' && connectionsDuring >= connBaseline + 1, `SSE 连接期间 /agv/stream/status connections=${connectionsDuring}（基线 ${connBaseline}，本脚本连接被统计）`)
const sse = await ssePromise
const stateFrames = sse.frames.filter((f) => f.event === 'state')
assert(sse.status === 200 && String(sse.contentType).includes('text/event-stream'), `SSE HTTP ${sse.status}，Content-Type=${sse.contentType}`)
assert(!sse.error, `SSE 30s 内无错误/未被网关中断（error=${sse.error ?? 'null'}）`)
assert(stateFrames.length >= 20 && stateFrames.length <= 36, `运行中 30s 收到 ${stateFrames.length} 帧（期望 20~36，≤1 帧/s）`)
assert(stateFrames.every((f) => f.parsed && Array.isArray(f.parsed.cars)), '每帧 data 是 LiveState JSON（含 cars）')
const frameGaps = stateFrames.slice(1).map((f, i) => f.at - stateFrames[i].at)
const minGap = frameGaps.length ? Math.min(...frameGaps) : 0
assert(minGap >= 400, `相邻帧最小间隔 ${minGap}ms ≥400ms（不会一 tick 多帧）`)
const positions = stateFrames.map((f) => {
  const car = findCar(f.parsed?.cars, taskCar.agvCode)
  return car ? `${car.x},${car.y}` : '?'
})
const distinctPositions = new Set(positions).size
assert(distinctPositions >= 10, `SSE 帧内车辆位置持续变化（${distinctPositions} 个不同位置）`)
assert(sse.heartbeats >= 1, `30s 内收到 ${sse.heartbeats} 次心跳（15s 一次）`)
const frameAvgBytes = stateFrames.length ? Math.round(stateFrames.reduce((sum, f) => sum + f.bytes, 0) / stateFrames.length) : 0
samples.sse = { frames: stateFrames.length, heartbeats: sse.heartbeats, avgFrameBytes: frameAvgBytes, minGapMs: minGap, distinctPositions, contentType: sse.contentType }
console.log(`  SSE 帧大小约 ${frameAvgBytes}B/帧`)

// 空闲时 SSE 不推 state 帧（无变化不推）
// 先等快照 etag 稳定（取消任务后 stepJob 下一次 idle 归一化会再改一次 description，属正常状态变化）
const idleFramesInfo = await (async () => {
  await cancelTasksOf(taskCar.agvCode)
  await waitUntilIdle(15000)
  await waitStableLive(10000)
  const idleSse = await collectSse('SSE-idle-6s', BASE + '/agv/stream', 6000)
  return { frames: idleSse.frames.filter((f) => f.event === 'state').length, status: idleSse.status, error: idleSse.error }
})()
assert(idleFramesInfo.status === 200 && idleFramesInfo.frames <= 1, `空闲 6s SSE 只收到 ${idleFramesInfo.frames} 帧 state（1 初始帧 + 无变化不推帧）`)

// 断开后连接数不应持续增长（服务端在写失败/心跳/30min 安全超时时清理；
// 注意可能有其它页面/浏览器同时连着 SSE，因此用“相对不增长”而不是绝对 0 断言）
const connAfterIdleAbort = (await http('SSE连接数-空闲连接断开后', '/agv/stream/status')).json?.data?.connections ?? 0
let statusAfter = await http('SSE连接数-断开后', '/agv/stream/status')
for (let i = 0; i < 5; i += 1) {
  await sleep(1000)
  statusAfter = await http('SSE连接数-断开后', '/agv/stream/status')
}
const connectionsAfter = statusAfter.json?.data?.connections ?? 0
assert(typeof connectionsAfter === 'number', `SSE 连接数可查询（断开后=${connectionsAfter}，空闲连接时=${connAfterIdleAbort}，30s 连接时=${connectionsDuring}，基线=${connBaseline}）`)
assert(connectionsAfter <= Math.max(connAfterIdleAbort, connectionsDuring, connBaseline) + 1, `SSE 断开后连接数不泄漏增长（${connectionsAfter} ≤ ${Math.max(connAfterIdleAbort, connectionsDuring, connBaseline) + 1}）`)
samples.sseConnections = { baseline: connBaseline, during: connectionsDuring, afterIdleAbort: connAfterIdleAbort, after: connectionsAfter }

// ---------------------------------------------------------------- 4. 响应体积
console.log('\n[4] 响应体积：/agv/live 替代 /agv/list + /map/current')
const listSize = (await http('车辆列表-体积', '/agv/list', { method: 'POST' })).bytes
const mapSize = (await http('当前地图-体积', '/map/current')).bytes
const liveSize = (await http('实时快照-体积', '/agv/live')).bytes
const oldPerSecond = listSize + mapSize
assert(liveSize < oldPerSecond, `单次 /agv/live ${liveSize}B < 旧 list+map ${oldPerSecond}B（体积降 ${Math.round((1 - liveSize / oldPerSecond) * 100)}%）`)
assert(liveSize < 8192, `/agv/live 体积 ${liveSize}B < 8KB（精简 plannedPath 生效；运行中车会带路径坐标）`)
samples.size = {
  oldListBytes: listSize,
  oldMapBytes: mapSize,
  oldPerSecondBytes: oldPerSecond,
  liveBytes: liveSize,
  sseFrameBytes: frameAvgBytes,
  idlePollBytesPer30s: idle304Bytes + idle200Bytes,
}

// ---------------------------------------------------------------- 5. 写后立刻读一致性
console.log('\n[5] 写后立刻读一致性（R-V2-08：每个写接口后立即读 /agv/live 与 /agv/list）')
const consistency = {}
async function bothCars(code) {
  const liveNow = await liveCars()
  const listNow = await listCars()
  return { live: findCar(liveNow, code), list: findCar(listNow, code), liveAll: liveNow, listAll: listNow }
}

// 5.1 batch
const batchRes = await http('批量加车', '/agv/batch/1', { method: 'POST' })
const newCode = batchRes.json?.data?.[0]
let pair = await bothCars(newCode)
assert(!!pair.live && !!pair.list, `batch 后立刻读到新车 ${newCode}（live=${!!pair.live}, list=${!!pair.list}）`)
consistency.batch = { code: newCode, live: !!pair.live, list: !!pair.list }

// 5.2 speed
const speedRes = await http('调速', '/agv/speed', { method: 'POST', body: { agvCode: 'AGV002', speed: 321 } })
pair = await bothCars('AGV002')
assert(speedRes.json?.code === 200 && pair.live?.speed === 321 && pair.list?.speed === 321, `speed 后立刻读 speed=${pair.live?.speed}/${pair.list?.speed}（期望 321）`)
await http('恢复速度', '/agv/speed', { method: 'POST', body: { agvCode: 'AGV002', speed: 150 } })
pair = await bothCars('AGV002')
assert(pair.live?.speed === 150, `恢复速度后立刻读 speed=${pair.live?.speed}`)
consistency.speed = { live: pair.live?.speed }

// 5.3 report（只上报坐标，不动任务）
const reportCar = (await liveCars()).find((c) => c.status === 'idle' && c.taskId == null)
const originX = reportCar?.x
const originY = reportCar?.y
const reportRes = await http('位置上报', '/agv/report', {
  method: 'POST',
  body: { agvCode: reportCar.agvCode, x: 5, y: 20, direction: 0, status: 'idle', battery: reportCar.battery, speed: 0, isCharge: 0 },
})
pair = await bothCars(reportCar.agvCode)
assert(reportRes.json?.code === 200 && pair.live?.x === 5 && pair.live?.y === 20 && pair.list?.x === 5, `report 后立刻读坐标 (${pair.live?.x},${pair.live?.y})/${pair.list?.x},${pair.list?.y}`)
await http('恢复坐标', '/agv/report', {
  method: 'POST',
  body: { agvCode: reportCar.agvCode, x: originX, y: originY, status: 'idle', battery: reportCar.battery, speed: 0, isCharge: 0 },
})
pair = await bothCars(reportCar.agvCode)
assert(pair.live?.x === originX && pair.live?.y === originY, `恢复坐标后立刻读 (${pair.live?.x},${pair.live?.y})`)
consistency.report = { x: 5, y: 20 }

// 5.4 stop / start（先确保有一辆运行中的车）
let runCar = (await liveCars()).find((c) => c.status === 'running' && c.taskId != null) ?? null
if (!runCar) {
  const stopStartCar = (await liveCars()).find((c) => c.status === 'idle' && c.taskId == null)
  if (stopStartCar) {
    await http('stop/start 准备任务', '/agv/path', {
      method: 'POST',
      body: {
        agvCode: stopStartCar.agvCode,
        taskCode: `STOPSTART-${Date.now()}`,
        startPoint: { id: 'S1', x: 2, y: 2 },
        endPoint: { id: 'S6', x: 37, y: 27 },
        currentPoint: { x: stopStartCar.x, y: stopStartCar.y },
      },
    })
    runCar = findCar(await liveCars(), stopStartCar.agvCode)
  }
}
if (runCar && runCar.taskId != null) {
  const stopRes = await http('暂停', '/agv/stop', { method: 'POST', body: { agvCode: runCar.agvCode } })
  pair = await bothCars(runCar.agvCode)
  assert(stopRes.json?.code === 200 && pair.live?.status === 'stopping' && pair.list?.status === 'stopping', `stop 后立刻读 status=${pair.live?.status}/${pair.list?.status}`)
  const startRes = await http('恢复', '/agv/start', { method: 'POST', body: { agvCode: runCar.agvCode } })
  pair = await bothCars(runCar.agvCode)
  assert(startRes.json?.code === 200 && pair.live?.status === 'running', `start 后立刻读 status=${pair.live?.status}`)
  consistency.stopStart = { status: pair.live?.status }
  await cancelTasksOf(runCar.agvCode)
  pair = await bothCars(runCar.agvCode)
  assert(pair.live?.status === 'idle' && pair.live?.taskId == null, `cancel 后立刻读 status=${pair.live?.status} taskId=${pair.live?.taskId}`)
} else {
  assert(false, 'stop/start 需要一辆运行中的车（未找到，跳过）')
}

// 5.5 charge
const chargeCar = (await liveCars()).find((c) => c.status === 'idle' && c.taskId == null)
const chargeRes = await http('遥控充电', `/agv/charge/${chargeCar.agvCode}`, { method: 'POST' })
pair = await bothCars(chargeCar.agvCode)
assert(chargeRes.json?.code === 200 && pair.live?.taskId != null && pair.live?.status === 'running', `charge 后立刻读 taskId=${pair.live?.taskId} status=${pair.live?.status}`)
await cancelTasksOf(chargeCar.agvCode)
pair = await bothCars(chargeCar.agvCode)
assert(pair.live?.status === 'idle' && pair.live?.taskId == null, `charge 任务取消后立刻读 status=${pair.live?.status}`)
consistency.charge = { agvCode: chargeCar.agvCode }

// 5.6 clear
const clearTarget = (await liveCars()).find((c) => c.status === 'idle' && c.taskId == null)
await http('清空前建任务', '/agv/path', {
  method: 'POST',
  body: {
    agvCode: clearTarget.agvCode,
    taskCode: `CLEAR-${Date.now()}`,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S4', x: 2, y: 27 },
    currentPoint: { x: clearTarget.x, y: clearTarget.y },
  },
})
const beforeClear = await bothCars(clearTarget.agvCode)
assert(beforeClear.live?.taskId != null, `clear 前车辆已有任务 taskId=${beforeClear.live?.taskId}`)
const clearRes = await http('清空任务', '/agv/clear', { method: 'POST' })
pair = await bothCars(clearTarget.agvCode)
assert(clearRes.json?.code === 200 && pair.live?.taskId == null && pair.live?.status === 'idle', `clear 后立刻读 status=${pair.live?.status} taskId=${pair.live?.taskId}`)
assert(pair.liveAll.every((c) => c.status === 'idle' && c.taskId == null), 'clear 后立刻读所有车均 idle/无任务')
consistency.clear = { cars: pair.liveAll.length }

// 5.7 saveMap
const mapBefore = (await http('地图-保存前', '/map/current')).json?.data
const saveRes = await http('保存地图', '/map/save', {
  method: 'POST',
  body: {
    mapName: mapBefore.mapName,
    width: mapBefore.width,
    height: mapBefore.height,
    cellSize: mapBefore.cellSize,
    description: `${mapBefore.description ?? ''}（realtime 验收保存）`,
    obstacles: mapBefore.obstacles,
    stations: mapBefore.stations,
  },
})
const liveAfterSave = await http('实时快照-保存地图后', '/agv/live')
const mapAfter = (await http('地图-保存后', '/map/current')).json?.data
assert(saveRes.json?.code === 200, 'POST /map/save 成功')
assert(liveAfterSave.json?.map?.version === (mapBefore.version ?? 0) + 1, `saveMap 后立刻读 live.map.version=${liveAfterSave.json?.map?.version}（期望 ${(mapBefore.version ?? 0) + 1}）`)
assert(mapAfter?.version === liveAfterSave.json?.map?.version, `/map/current 读缓存版本一致（${mapAfter?.version}）`)
const mapEtagAfter = (await http('地图-保存后ETag', '/map/current')).headers.get('etag')
assert(mapEtagAfter !== mapEtag, `saveMap 后地图 ETag 变化（${mapEtag} → ${mapEtagAfter}）`)
consistency.saveMap = { versionBefore: mapBefore.version, versionAfter: mapAfter?.version }

// ---------------------------------------------------------------- 6. vite 5173 代理（可选）
console.log('\n[6] 经 vite 5173 代理收 SSE（可选，5173 未启动则 SKIP）')
const viteBase = VITE.replace(/\/api$/, '')
let viteProbe = null
try {
  viteProbe = await fetch(viteBase + '/', { signal: AbortSignal.timeout(4000) })
} catch (e) {
  viteProbe = null
}
if (!viteProbe || !viteProbe.ok) {
  console.log(`  - SKIP：vite dev server 未运行（${viteBase}）`)
  samples.vite = { skipped: true }
} else {
  const viteProxyCar = (await liveCars()).find((c) => c.status === 'idle' && c.taskId == null)
  await http('5173-创建任务', '/agv/path', {
    method: 'POST',
    base: VITE,
    body: {
      agvCode: viteProxyCar.agvCode,
      taskCode: `VITE-${Date.now()}`,
      startPoint: { id: 'S1', x: 2, y: 2 },
      endPoint: { id: 'S6', x: 37, y: 27 },
      currentPoint: { x: viteProxyCar.x, y: viteProxyCar.y },
    },
  })
  const viteSse = await collectSse('SSE-vite-10s', VITE + '/agv/stream', 10000)
  const viteFrames = viteSse.frames.filter((f) => f.event === 'state').length
  assert(viteSse.status === 200 && String(viteSse.contentType).includes('text/event-stream'), `经 5173 代理 SSE Content-Type=${viteSse.contentType}`)
  assert(viteFrames >= 4, `经 5173 代理 10s 收到 ${viteFrames} 帧（≥4，未被代理缓冲）`)
  await cancelTasksOf(viteProxyCar.agvCode)
  samples.vite = { frames: viteFrames, contentType: viteSse.contentType }
}

// ---------------------------------------------------------------- 清理
console.log('\n[7] 清理验证任务（保留车辆与地图版本）')
await http('清理-清空任务', '/agv/clear', { method: 'POST' })
await sleep(500)
const afterCleanup = await liveCars()
assert(afterCleanup.every((c) => c.status === 'idle' && c.taskId == null), `清理后全部车辆 idle（${afterCleanup.length} 台）`)

// ---------------------------------------------------------------- 汇总
const summary = {
  base: BASE,
  viteBase: VITE,
  startedAt: new Date().toISOString(),
  failures,
  passed: results.length - failures,
  totalCalls: results.length,
  failed: results.filter((r) => !r.ok),
  samples,
}
writeFileSync(`${OUT}/api-verify-realtime-result.json`, JSON.stringify({ summary, results, consistency }, null, 2), 'utf8')

console.log('\n================ 汇总 ================')
console.log(`断言失败 ${failures} 个`)
console.log(`\n关键样例：`)
console.log(`  /agv/live: etag=${samples.live?.etag} ${samples.live?.bytes}B，cars=${samples.live?.cars}，paths=${samples.live?.paths}`)
console.log(`  空闲 30s 轮询: 304=${samples.idle?.count304} 200=${samples.idle?.count200}，200 字节合计=${samples.idle?.idle200Bytes}`)
console.log(`  运行 30s SSE: ${JSON.stringify(samples.sse)}`)
console.log(`  体积: ${JSON.stringify(samples.size)}`)
console.log(`  写后一致性: ${JSON.stringify(consistency)}`)
console.log(`  vite 5173: ${JSON.stringify(samples.vite)}`)
console.log(`\n明细已写入 ${OUT}/api-verify-realtime-result.json`)
if (failures > 0) process.exitCode = 1
