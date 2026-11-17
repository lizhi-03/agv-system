// 仿真 tick 压测：对比优化前后 tick 耗时、数据库语句数、写接口延迟。
//
// 用法（直连 service-agv，不经网关）：
//   AGV_API_BASE=http://localhost:18000 \
//   AGV_LOG_FILE=logs/service-agv.log \          # 可选：解析 stepJob 的 tick 耗时（需 --logging.level.com.lizhi.job=debug）
//   AGV_MYSQL_PASSWORD=*** \                      # 可选：通过 mysql 客户端采集 Com_insert/update/delete/select
//   node scripts/bench-tick.mjs
//
// 可调参数（环境变量）：BENCH_CARS=20  BENCH_RUNNING=10  BENCH_SECONDS=30
// 注意：会批量加车并下发任务，请在测试库上运行。
import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync, statSync } from 'node:fs'

const BASE = process.env.AGV_API_BASE || 'http://localhost:8080/api'
const CARS = Number(process.env.BENCH_CARS || 20)
const RUNNING = Number(process.env.BENCH_RUNNING || 10)
const SECONDS = Number(process.env.BENCH_SECONDS || 30)
const LOG_FILE = process.env.AGV_LOG_FILE
const MYSQL_PASSWORD = process.env.AGV_MYSQL_PASSWORD
const MYSQL_USER = process.env.AGV_MYSQL_USER || 'root'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function call(path, { method = 'POST', body } = {}) {
  const startedAt = performance.now()
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  const ms = performance.now() - startedAt
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: res.status, json, ms, bytes: text.length }
}

function dbCounters() {
  if (!MYSQL_PASSWORD) return null
  try {
    const out = execFileSync('mysql', ['-u' + MYSQL_USER, '-N', '-e',
      "show global status where Variable_name in ('Com_insert','Com_update','Com_delete','Com_select','Com_commit')"],
      { env: { ...process.env, MYSQL_PWD: MYSQL_PASSWORD }, encoding: 'utf8' })
    return Object.fromEntries(out.trim().split('\n').map((l) => { const [k, v] = l.split('\t'); return [k, Number(v)] }))
  } catch (e) {
    console.warn('采集 MySQL 计数失败（跳过）:', e.message.split('\n')[0])
    return null
  }
}

function percentile(sorted, p) {
  if (!sorted.length) return null
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}
const stats = (values) => {
  const s = [...values].sort((a, b) => a - b)
  const r = (v) => (v == null ? null : Math.round(v * 10) / 10)
  return { n: s.length, p50: r(percentile(s, 50)), p95: r(percentile(s, 95)), p99: r(percentile(s, 99)), max: r(s[s.length - 1] ?? null) }
}

console.log(`===== tick 压测 ${BASE}：${CARS} 台车 / ${RUNNING} 台运行 / ${SECONDS}s =====`)

// 1. 准备车辆
let cars = (await call('/agv/list')).json?.data ?? []
if (cars.length < CARS) {
  await call(`/agv/batch/${CARS - cars.length}`)
  cars = (await call('/agv/list')).json?.data ?? []
}
const stations = ((await call('/map/station', { method: 'GET' })).json?.data ?? []).filter((s) => s.type !== 'charge')
if (stations.length < 2) throw new Error('当前地图作业站点不足 2 个，无法压测')
await call('/agv/clear')
await sleep(1500)

// 2. 给 RUNNING 台车下发任务（起终点取距离最远的站点对，尽量跑满整个采样窗口）
const codes = cars.map((c) => c.agvCode).sort().slice(0, RUNNING)
const dist = (a, b) => Math.abs(a.x - b.x) + Math.abs(a.y - b.y)
const pairs = []
for (const a of stations) for (const b of stations) if (a !== b) pairs.push([a, b])
pairs.sort((p, q) => dist(q[0], q[1]) - dist(p[0], p[1]))
const logOffset = LOG_FILE && existsSync(LOG_FILE) ? statSync(LOG_FILE).size : 0
const before = dbCounters()
const startedAt = Date.now()
const startLatency = []
let pathBytes = 0
for (let i = 0; i < codes.length; i++) {
  const [from, to] = pairs[i % pairs.length]
  const res = await call('/agv/path', { body: { agvCode: codes[i], startPoint: from, endPoint: to } })
  if (res.json?.code !== 200) console.warn(`下发任务失败 ${codes[i]}:`, res.json?.msg)
  startLatency.push(res.ms)
  pathBytes = Math.max(pathBytes, res.bytes)
}

// 3. 采样窗口内持续打写接口（调速，不改变运动）和读接口，观察是否被 tick 阻塞
const writeLatency = []
const liveLatency = []
while (Date.now() - startedAt < SECONDS * 1000) {
  const code = codes[Math.floor(Math.random() * codes.length)]
  writeLatency.push((await call('/agv/speed', { body: { agvCode: code, speed: 150 } })).ms)
  liveLatency.push((await call('/agv/live', { method: 'GET' })).ms)
  await sleep(40)
}
const after = dbCounters()
const elapsed = (Date.now() - startedAt) / 1000

// 4. 汇总
const report = {
  base: BASE, cars: cars.length, running: codes.length, seconds: Math.round(elapsed),
  startTaskMs: stats(startLatency),
  maxPathResponseBytes: pathBytes,
  speedWriteMs: stats(writeLatency),
  liveReadMs: stats(liveLatency),
}
if (before && after) {
  report.dbPerSecond = Object.fromEntries(Object.keys(after).map((k) => [k, Math.round((after[k] - before[k]) / elapsed)]))
}
if (LOG_FILE && existsSync(LOG_FILE)) {
  const text = readFileSync(LOG_FILE).subarray(logOffset).toString('utf8')
  const ticks = [...text.matchAll(/stepJob[^\n]*?耗时 ?(\d+)ms/g)].map((m) => Number(m[1]))
  report.tickMs = stats(ticks)
}
const tasks = (await call('/task/page', { body: { page: 1, pageSize: 100 } })).json?.data?.records ?? []
report.taskStatus = tasks.filter((t) => codes.includes(t.agvCode)).slice(0, codes.length)
  .reduce((acc, t) => ({ ...acc, [t.status]: (acc[t.status] ?? 0) + 1 }), {})
console.log(JSON.stringify(report, null, 2))
