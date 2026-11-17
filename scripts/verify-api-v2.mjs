// AGV 后端 V2 接口实测脚本（真实 HTTP，经网关 http://localhost:8080/api）
// 覆盖：
//   1) 旧接口回归（map/agv/task/log 分页、业务错误仍 HTTP 200 + code=204）
//   2) POST /agv/batch/{n}：从现有 AGVplusNNNN 最大编号 +1 连续编号、返回 string[]、重复调用不重复注册
//   3) POST /agv/preview：返回路径且不落库（/task/page total 不变、车辆状态不变、不写占用路径）
//   4) batch 新车能接 /agv/path 任务并真实移动（本轮关键验收）
//   5) POST /task/page 的 status / agvCode 过滤，POST /log/page 的 level / message 过滤
//   6) getAllAgv（/agv/list）性能、CORS 响应头
//
// 用法：node scripts/verify-api-v2.mjs
// 结果明细写入 .verify/api-verify-v2-result.json
import { mkdirSync, writeFileSync } from 'node:fs'

const BASE = process.env.AGV_API_BASE || 'http://localhost:8080/api'
const OUT = './.verify'
mkdirSync(OUT, { recursive: true })

const results = []
let failures = 0
const samples = {}

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

/**
 * 发起一次接口调用。
 * @param {string} name 用例名
 * @param {string} path 相对网关路径（/agv/list 等）
 * @param {object} [opts] method/body/headers/expect（expect 为期望业务 code，默认 200）
 */
async function call(name, path, { method = 'POST', body, headers, expect = 200 } = {}) {
  const url = BASE + path
  const started = Date.now()
  try {
    const res = await fetch(url, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json', ...(headers ?? {}) } : headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(20000),
    })
    const text = await res.text()
    let json
    try {
      json = JSON.parse(text)
    } catch {
      throw new Error(`非 JSON 响应 (HTTP ${res.status}): ${text.slice(0, 200)}`)
    }
    const ok = res.ok && json.code === expect
    if (!ok) failures += 1
    const record = {
      name,
      path,
      method,
      http: res.status,
      code: json.code,
      expect,
      ok,
      ms: Date.now() - started,
      data: json.data,
      msg: json.msg,
    }
    results.push(record)
    if (!ok) console.log(`  [调用不符预期] ${name} ${method} ${path} → HTTP ${res.status} code=${json.code} msg=${json.msg ?? ''}`)
    return { http: res.status, headers: res.headers, ...json }
  } catch (e) {
    failures += 1
    results.push({ name, path, method, ok: false, error: String(e.message || e) })
    console.log(`  [调用异常] ${name} ${method} ${path}: ${e.message || e}`)
    return null
  }
}

const codesOf = (cars) => (cars ?? []).map((c) => c.agvCode)
const maxPlusIndex = (cars) => {
  let max = 0
  for (const code of codesOf(cars)) {
    const m = /^AGVplus(\d+)$/.exec(code ?? '')
    if (m) max = Math.max(max, parseInt(m[1], 10))
  }
  return max
}
const plusIndex = (code) => parseInt(/^AGVplus(\d+)$/.exec(code ?? '')?.[1] ?? 'NaN', 10)

/**
 * 服务端过滤断言：先用全量查询计算期望条数，再比对过滤查询的 total。
 * 支持最多 3 次重试，避免其它并发操作（实时 stepJob/其它测试）造成瞬时不一致。
 */
/**
 * 按页拉全量：服务端 pageSize 上限为 100，这里逐页取完再合并成与单次分页相同的结构。
 */
const PAGE_MAX = 100
async function callAllPages(name, path, extra = {}) {
  let merged = null
  for (let page = 1; page <= 200; page += 1) {
    const res = await call(name, path, { method: 'POST', body: { page, pageSize: PAGE_MAX, ...extra } })
    if (!res) return res
    const records = res?.data?.records ?? []
    if (!merged) merged = { ...res, data: { ...(res.data ?? {}), records: [] } }
    merged.data.records.push(...records)
    if (records.length < PAGE_MAX) break
  }
  return merged
}

async function expectFilterTotal(label, fetchFull, fetchFiltered, predicate, alsoCheckRecords) {
  let detail = null
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const full = await fetchFull()
    const filtered = await fetchFiltered()
    if (!full || !filtered) return false
    const records = full?.data?.records ?? []
    const expected = records.filter(predicate).length
    detail = { expected, actual: filtered?.data?.total, fullTotal: full?.data?.total }
    if (filtered?.data?.total === expected && alsoCheckRecords(filtered?.data?.records ?? [], expected)) {
      return assert(true, `${label} total=${filtered?.data?.total}（期望 ${expected}）`)
    }
    await sleep(400)
  }
  return assert(false, `${label} total 不匹配: ${JSON.stringify(detail)}`)
}

console.log(`\n===== AGV 后端 V2 验收 ${BASE} =====`)

// ---------------------------------------------------------------- 0. 前置
console.log('\n[0] 前置：读取车队')
const list0Res = await call('车辆列表', '/agv/list', { method: 'POST' })
const cars0 = list0Res?.data ?? []
assert(Array.isArray(list0Res?.data) && cars0.length >= 3, `POST /agv/list 返回车辆数组（${cars0.length} 台）`)
assert(cars0.every((a) => typeof a.agvCode === 'string'), '车辆 JSON 字段名为 agvCode（旧契约不回归）')
const maxBefore0 = maxPlusIndex(cars0)

// ---------------------------------------------------------------- 1. 旧接口回归
console.log('\n[1] 旧接口回归（其余 17 个接口契约不变）')
const mapRes = await call('当前地图', '/map/current', { method: 'GET' })
assert(mapRes?.code === 200 && mapRes.data?.width > 0 && mapRes.data?.height > 0, `GET /map/current 返回地图（${mapRes?.data?.width}x${mapRes?.data?.height}）`)
const stationRes = await call('站点列表', '/map/station', { method: 'GET' })
assert(stationRes?.code === 200 && Array.isArray(stationRes.data) && stationRes.data.length > 0, `GET /map/station 返回 ${stationRes?.data?.length} 个站点`)
const getAgvRes = await call('单车查询', '/agv/AGV001', { method: 'GET' })
assert(getAgvRes?.code === 200 && getAgvRes.data?.agvCode === 'AGV001', 'GET /agv/AGV001 返回正确车辆')
const getMissRes = await call('单车查询-不存在', '/agv/AGV_NOT_EXIST', { method: 'GET', expect: 204 })
assert(getMissRes?.code === 204 && typeof getMissRes.msg === 'string', `GET 不存在车辆返回 code=204 + msg（实际 ${getMissRes?.code}）`)
const taskPageRes = await call('任务分页', '/task/page', { method: 'POST', body: { page: 1, pageSize: 5 } })
assert(taskPageRes?.code === 200 && typeof taskPageRes.data?.total === 'number' && Array.isArray(taskPageRes.data?.records), 'POST /task/page 返回 total + records')
const logPageRes = await call('日志分页', '/log/page', { method: 'POST', body: { page: 1, pageSize: 5 } })
assert(logPageRes?.code === 200 && typeof logPageRes.data?.total === 'number' && Array.isArray(logPageRes.data?.records), 'POST /log/page 返回 total + records')
const speedRes = await call('调速', '/agv/speed', { method: 'POST', body: { agvCode: 'AGV002', speed: 150 } })
assert(speedRes?.code === 200, 'POST /agv/speed 返回 code=200')
const stopMissRes = await call('停止-不存在车辆', '/agv/stop', { method: 'POST', body: { agvCode: 'AGV_NOT_EXIST' }, expect: 204 })
assert(stopMissRes?.code === 204, `POST /agv/stop 不存在车辆返回 code=204（实际 ${stopMissRes?.code}）`)
const cancelMissRes = await call('取消-不存在任务', '/task/cancel/999999', { method: 'GET', expect: 204 })
assert(cancelMissRes?.code === 204, `GET /task/cancel/999999 返回 code=204（实际 ${cancelMissRes?.code}）`)
const page1Res = await call('任务分页-第1页', '/task/page', { method: 'POST', body: { page: 1, pageSize: 2 } })
const page2Res = await call('任务分页-第2页', '/task/page', { method: 'POST', body: { page: 2, pageSize: 2 } })
assert((page1Res?.data?.records ?? []).length <= 2 && (page2Res?.data?.records ?? []).length <= 2, 'page/pageSize 从 1 开始生效（每页 ≤ pageSize）')
assert(page1Res?.data?.total === page2Res?.data?.total, '分页 total 不随页码变化')

// ---------------------------------------------------------------- 2. batch 连续编号
console.log('\n[2] POST /agv/batch/{n}：连续编号 / string[] / 不重复注册')
let created1 = []
let nums1 = []
let maxBefore = maxBefore0
for (let attempt = 0; attempt < 3; attempt += 1) {
  const fresh = await call(`车辆列表-batch前${attempt ? `·重试${attempt}` : ''}`, '/agv/list', { method: 'POST' })
  maxBefore = maxPlusIndex(fresh?.data ?? [])
  const r = await call(`批量加车2台${attempt ? `·重试${attempt}` : ''}`, '/agv/batch/2', { method: 'POST' })
  created1 = Array.isArray(r?.data) ? r.data : []
  nums1 = created1.map(plusIndex)
  if (nums1.length === 2 && nums1[0] === maxBefore + 1 && nums1[1] === maxBefore + 2) break
  await sleep(300)
}
samples.batch = { request: '/agv/batch/2', response: created1 }
assert(Array.isArray(created1) && created1.length === 2, `batch 返回 data: string[]（长度 ${created1.length}）`)
assert(created1.every((c) => typeof c === 'string' && /^AGVplus\d{4,}$/.test(c)), `编号格式 AGVplusNNNN（${created1.join(', ')}）`)
assert(nums1[0] === maxBefore + 1 && nums1[1] === maxBefore + 2, `从现有最大编号 ${maxBefore} 起连续 +1（${created1.join(', ')}）`)

const batch2 = await call('再次批量加车2台', '/agv/batch/2', { method: 'POST' })
const created2 = Array.isArray(batch2?.data) ? batch2.data : []
const nums2 = created2.map(plusIndex)
assert(created2.length === 2, `重复调用返回 2 个新编号（${created2.join(', ')}）`)
assert(nums2[0] === maxBefore + 3 && nums2[1] === maxBefore + 4, `重复调用不重复注册，编号继续 +1（${created2.join(', ')}）`)
assert(created2.every((c) => !created1.includes(c)), '重复调用未返回已存在的车辆编号')

const listAfterBatch = await call('车辆列表-加车后', '/agv/list', { method: 'POST' })
const codesAfterBatch = codesOf(listAfterBatch?.data)
const allCreated = [...created1, ...created2]
assert(allCreated.every((c) => codesAfterBatch.includes(c)), '新编号全部出现在 /agv/list')
assert(codesAfterBatch.length === cars0.length + 4, `车队数量 +4（${cars0.length} → ${codesAfterBatch.length}）`)
const newCars = (listAfterBatch?.data ?? []).filter((a) => allCreated.includes(a.agvCode))
assert(newCars.length === 4 && newCars.every((a) => a.status === 'idle' && typeof a.x === 'number' && typeof a.y === 'number'), '新增车辆立即可见且状态/idle 坐标正常')

const bad0 = await call('批量加车-0', '/agv/batch/0', { method: 'POST', expect: 204 })
assert(bad0?.code === 204 && typeof bad0.msg === 'string', `n=0 返回 code=204 + msg（${bad0?.msg}）`)
const bad51 = await call('批量加车-51', '/agv/batch/51', { method: 'POST', expect: 204 })
assert(bad51?.code === 204 && typeof bad51.msg === 'string', `n=51 返回 code=204 + msg（${bad51?.msg}）`)
const badAbc = await call('批量加车-非数字', '/agv/batch/abc', { method: 'POST', expect: 204 })
assert(badAbc?.code === 204 && typeof badAbc.msg === 'string', `n=abc（非法格式）返回 code=204 + msg（${badAbc?.msg}）`)

// ---------------------------------------------------------------- 3. preview 只预览不落库
console.log('\n[3] POST /agv/preview：返回路径 / 不落库 / 非法入参 204')
const newCar = created1[0] ?? 'AGVplus0001'
const previewCarBefore = await call('新车状态-预览前', `/agv/${newCar}`, { method: 'GET' })
const taskTotalBefore = (await call('任务分页-预览前', '/task/page', { method: 'POST', body: { page: 1, pageSize: 1 } }))?.data?.total

const preview1 = await call('预览路径(缺省当前位置)', '/agv/preview', {
  method: 'POST',
  body: { agvCode: newCar, startPoint: { id: 'S1', x: 2, y: 2 }, endPoint: { id: 'S6', x: 37, y: 27 } },
})
assert(preview1?.code === 200 && Array.isArray(preview1.data) && preview1.data.length > 2, `preview 返回路径数组（${preview1?.data?.length} 个节点）`)
assert((preview1?.data ?? []).every((n) => typeof n.x === 'number' && typeof n.y === 'number'), '预览节点含 x/y 坐标')
samples.preview = {
  request: { agvCode: newCar, startPoint: { id: 'S1', x: 2, y: 2 }, endPoint: { id: 'S6', x: 37, y: 27 } },
  pathLength: preview1?.data?.length,
  first: preview1?.data?.[0],
  last: preview1?.data?.[preview1.data.length - 1],
}

const preview2 = await call('预览路径(指定当前位置)', '/agv/preview', {
  method: 'POST',
  body: {
    agvCode: newCar,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S6', x: 37, y: 27 },
    currentPoint: { x: 20, y: 1 },
  },
})
assert(preview2?.code === 200 && (preview2?.data?.length ?? 0) > 2, `指定 currentPoint 也能返回路径（${preview2?.data?.length} 个节点）`)

const previewBad1 = await call('预览-缺 agvCode', '/agv/preview', { method: 'POST', body: { startPoint: { x: 2, y: 2 }, endPoint: { x: 37, y: 27 } }, expect: 204 })
assert(previewBad1?.code === 204 && typeof previewBad1.msg === 'string', `缺 agvCode → code=204 + msg（${previewBad1?.msg}）`)
const previewBad2 = await call('预览-缺终点', '/agv/preview', { method: 'POST', body: { agvCode: newCar, startPoint: { x: 2, y: 2 } }, expect: 204 })
assert(previewBad2?.code === 204 && typeof previewBad2.msg === 'string', `缺 endPoint → code=204 + msg（${previewBad2?.msg}）`)
const previewBad3 = await call('预览-车辆不存在', '/agv/preview', { method: 'POST', body: { agvCode: 'AGV_NOT_EXIST', startPoint: { x: 2, y: 2 }, endPoint: { x: 37, y: 27 } }, expect: 204 })
assert(previewBad3?.code === 204 && typeof previewBad3.msg === 'string', `车辆不存在 → code=204 + msg（${previewBad3?.msg}）`)
const previewBad4 = await call('预览-坐标越界', '/agv/preview', { method: 'POST', body: { agvCode: newCar, startPoint: { x: 999, y: 2 }, endPoint: { x: 37, y: 27 } }, expect: 204 })
assert(previewBad4?.code === 204 && typeof previewBad4.msg === 'string', `坐标越界 → code=204 + msg（${previewBad4?.msg}）`)
const previewBad5 = await call('预览-空请求体', '/agv/preview', { method: 'POST', body: {}, expect: 204 })
assert(previewBad5?.code === 204 && typeof previewBad5.msg === 'string', `空请求体 → code=204 + msg（${previewBad5?.msg}）`)

const taskTotalAfterPreview = (await call('任务分页-预览后', '/task/page', { method: 'POST', body: { page: 1, pageSize: 1 } }))?.data?.total
assert(taskTotalAfterPreview === taskTotalBefore, `preview 后 /task/page total 不变（${taskTotalBefore} → ${taskTotalAfterPreview}）`)
const previewCarAfter = await call('新车状态-预览后', `/agv/${newCar}`, { method: 'GET' })
assert(previewCarAfter?.data?.status === 'idle' && previewCarAfter?.data?.taskId == null, `preview 不改车辆状态（status=${previewCarAfter?.data?.status}, taskId=${previewCarAfter?.data?.taskId}）`)
assert((previewCarAfter?.data?.agvPaths?.length ?? 0) === 0, 'preview 不写占用路径（agvPaths 为空）')
const listAfterPreview = await call('车辆列表-预览后', '/agv/list', { method: 'POST' })
assert((listAfterPreview?.data ?? []).length === codesAfterBatch.length, 'preview 不会新增车辆')

// ---------------------------------------------------------------- 4. 新车接任务并真实移动
console.log('\n[4] batch 新车接 /agv/path 任务并真实移动（本轮关键验收）')
const beforeMove = await call('新车状态-任务前', `/agv/${newCar}`, { method: 'GET' })
const pathOnNewCar = await call('新车创建任务', '/agv/path', {
  method: 'POST',
  body: {
    agvCode: newCar,
    taskCode: `V2-${Date.now()}`,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S6', x: 37, y: 27 },
    currentPoint: { x: beforeMove?.data?.x ?? 0, y: beforeMove?.data?.y ?? 0 },
  },
})
assert(pathOnNewCar?.code === 200 && Array.isArray(pathOnNewCar.data) && pathOnNewCar.data.length > 2, `batch 新车成功接任务并返回路径（${pathOnNewCar?.data?.length} 个节点）`)
await sleep(2600)
const afterMove = await call('新车状态-任务后', `/agv/${newCar}`, { method: 'GET' })
const moved = beforeMove?.data && afterMove?.data && (beforeMove.data.x !== afterMove.data.x || beforeMove.data.y !== afterMove.data.y)
samples.newCarTask = {
  agvCode: newCar,
  before: { x: beforeMove?.data?.x, y: beforeMove?.data?.y, status: beforeMove?.data?.status },
  after: { x: afterMove?.data?.x, y: afterMove?.data?.y, status: afterMove?.data?.status, taskId: afterMove?.data?.taskId, pathLen: afterMove?.data?.plannedPath?.length },
}
assert(afterMove?.data?.taskId != null, `新车已关联任务（taskId=${afterMove?.data?.taskId}）`)
assert(afterMove?.data?.status === 'running' || afterMove?.data?.status === 'stopping', `新车开始运行（status=${afterMove?.data?.status}）`)
assert(moved, `新车真实移动：(${beforeMove?.data?.x},${beforeMove?.data?.y}) → (${afterMove?.data?.x},${afterMove?.data?.y})`)
assert((afterMove?.data?.plannedPath?.length ?? 0) > 0, `新车存在规划路径（${afterMove?.data?.plannedPath?.length} 个节点）`)
await sleep(1200)
const afterMove2 = await call('新车状态-任务后(继续)', `/agv/${newCar}`, { method: 'GET' })
assert(
  afterMove2?.data && (afterMove2.data.x !== afterMove?.data?.x || afterMove2.data.y !== afterMove?.data?.y || afterMove2.data.status === 'idle'),
  `stepJob 持续推进新车：(${afterMove?.data?.x},${afterMove?.data?.y}) → (${afterMove2?.data?.x},${afterMove2?.data?.y})`,
)

// ---------------------------------------------------------------- 5. 服务端筛选
console.log('\n[5] POST /task/page 与 /log/page 服务端过滤')
const fetchAllTasks = () => callAllPages('任务全量', '/task/page')
const fetchTasksFiltered = (extra, name) => callAllPages(name, '/task/page', extra)

await expectFilterTotal(
  'task.status=processing',
  fetchAllTasks,
  () => fetchTasksFiltered({ status: 'processing' }, '任务分页-status过滤'),
  (t) => t.status === 'processing',
  (records) => records.every((t) => t.status === 'processing'),
)
await expectFilterTotal(
  `task.agvCode=${newCar}`,
  fetchAllTasks,
  () => fetchTasksFiltered({ agvCode: newCar }, '任务分页-agvCode过滤'),
  (t) => t.agvCode === newCar,
  (records) => records.length > 0 && records.every((t) => t.agvCode === newCar),
)
await expectFilterTotal(
  'task.status=completed',
  fetchAllTasks,
  () => fetchTasksFiltered({ status: 'completed' }, '任务分页-status=completed'),
  (t) => t.status === 'completed',
  (records) => records.every((t) => t.status === 'completed'),
)

const allTasksRes = await fetchAllTasks()
const allTaskRecords = allTasksRes?.data?.records ?? []
const taskToken = (allTaskRecords[0]?.taskCode ?? '').slice(0, 4)
if (taskToken) {
  await expectFilterTotal(
    `task.taskCode LIKE ${taskToken}`,
    fetchAllTasks,
    () => fetchTasksFiltered({ taskCode: taskToken }, '任务分页-taskCode过滤'),
    (t) => (t.taskCode ?? '').includes(taskToken),
    (records) => records.length > 0 && records.every((t) => (t.taskCode ?? '').includes(taskToken)),
  )
}
const newCarProcessing = await callAllPages('任务分页-status+agvCode 组合过滤', '/task/page', { status: 'processing', agvCode: newCar })
assert(
  (newCarProcessing?.data?.records ?? []).every((t) => t.status === 'processing' && t.agvCode === newCar),
  `status+agvCode 组合过滤结果正确（total=${newCarProcessing?.data?.total}）`,
)

const fetchAllLogs = () => callAllPages('日志全量', '/log/page')
const fetchLogsFiltered = (extra, name) => callAllPages(name, '/log/page', extra)
const allLogsRes = await fetchAllLogs()
const allLogRecords = allLogsRes?.data?.records ?? []
const levelCounts = {}
for (const log of allLogRecords) levelCounts[log.level ?? 'null'] = (levelCounts[log.level ?? 'null'] ?? 0) + 1
const levelOrder = Object.entries(levelCounts).sort((a, b) => b[1] - a[1])
const filterLevel = levelOrder[0]?.[0] && levelOrder[0][0] !== 'null' ? levelOrder[0][0] : 'info'
await expectFilterTotal(
  `log.level=${filterLevel}`,
  fetchAllLogs,
  () => fetchLogsFiltered({ level: filterLevel }, '日志分页-level过滤'),
  (l) => l.level === filterLevel,
  (records) => records.every((l) => l.level === filterLevel),
)
const logMessage = (allLogRecords.find((l) => typeof l.message === 'string' && l.message.length >= 3)?.message ?? '').slice(0, 3)
if (logMessage) {
  await expectFilterTotal(
    `log.message LIKE ${logMessage}`,
    fetchAllLogs,
    () => fetchLogsFiltered({ message: logMessage }, '日志分页-message过滤'),
    (l) => (l.message ?? '').includes(logMessage),
    (records) => records.length > 0 && records.every((l) => (l.message ?? '').includes(logMessage)),
  )
}
const tasksFilteredSample = await fetchTasksFiltered({ status: 'completed' }, '任务筛选样例')
const logsFilteredSample = await fetchLogsFiltered({ level: filterLevel }, '日志筛选样例')
samples.filters = {
  task: { statusProcessing: newCarProcessing?.data?.total, allFiltered: tasksFilteredSample?.data?.total },
  log: { level: filterLevel, levelTotal: logsFilteredSample?.data?.total, messageToken: logMessage },
  taskTotal: allTasksRes?.data?.total,
  logTotal: allLogsRes?.data?.total,
}

// ---------------------------------------------------------------- 6. 性能与 CORS
console.log('\n[6] getAllAgv 性能与 CORS')
const perfStart = Date.now()
const perfList = await call('车辆列表-性能', '/agv/list', { method: 'POST' })
const perfMs = Date.now() - perfStart
assert(perfList?.code === 200 && perfMs < 1500, `POST /agv/list 往返 ${perfMs}ms < 1500ms（${perfList?.data?.length} 台车，已去 N+1）`)
samples.performance = { agvListMs: perfMs, carCount: perfList?.data?.length }

const origin = 'http://localhost:5173'
let corsHeader = null
let preflightStatus = null
let preflightHeader = null
try {
  const corsRes = await fetch(`${BASE}/agv/list`, {
    method: 'POST',
    headers: { Origin: origin },
    signal: AbortSignal.timeout(10000),
  })
  await corsRes.text()
  corsHeader = corsRes.headers.get('access-control-allow-origin')
  const preflight = await fetch(`${BASE}/agv/list`, {
    method: 'OPTIONS',
    headers: {
      Origin: origin,
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type',
    },
    signal: AbortSignal.timeout(10000),
  })
  await preflight.text()
  preflightStatus = preflight.status
  preflightHeader = {
    allowOrigin: preflight.headers.get('access-control-allow-origin'),
    allowMethods: preflight.headers.get('access-control-allow-methods'),
  }
} catch (e) {
  failures += 1
  console.log(`  [CORS 请求异常] ${e.message || e}`)
}
samples.cors = { origin, responseAllowOrigin: corsHeader, preflightStatus, preflightHeader }
assert(!!corsHeader && (corsHeader === '*' || corsHeader === origin), `带 Origin 请求返回 Access-Control-Allow-Origin=${corsHeader}`)
assert(!String(corsHeader ?? '').includes(','), `Access-Control-Allow-Origin 只有一个值（无网关/服务重复叠加）`)
assert(preflightStatus === 200 && !!preflightHeader?.allowOrigin, `OPTIONS 预检通过（status=${preflightStatus}, allow-origin=${preflightHeader?.allowOrigin}）`)

// ---------------------------------------------------------------- 7. 清理本次任务（不动其它演示数据）
console.log('\n[7] 清理本次验证创建的任务（保留车辆）')
for (const code of allCreated) {
  const carTasks = await call(`清理-查询任务 ${code}`, '/task/page', { method: 'POST', body: { page: 1, pageSize: 50, agvCode: code } })
  for (const task of carTasks?.data?.records ?? []) {
    if (['processing', 'paused', 'waiting'].includes(task.status)) {
      await call(`清理-取消任务 ${task.taskId}`, `/task/cancel/${task.taskId}`, { method: 'GET' })
    }
  }
}
const afterCleanup = await callAllPages('清理后-任务分页', '/task/page', { status: 'processing', agvCode: newCar })
assert((afterCleanup?.data?.records ?? []).length === 0, `本次验证任务已取消（剩余进行中 ${afterCleanup?.data?.total ?? 0}）`)

// ---------------------------------------------------------------- 汇总
const summary = {
  base: BASE,
  startedAt: new Date().toISOString(),
  totalCalls: results.length,
  failures,
  passed: results.filter((r) => r.ok).length,
  failed: results.filter((r) => !r.ok),
  samples,
}
writeFileSync(`${OUT}/api-verify-v2-result.json`, JSON.stringify({ summary, results }, null, 2), 'utf8')

console.log('\n================ 汇总 ================')
console.log(`调用接口 ${summary.totalCalls} 次，成功 ${summary.passed}，失败 ${summary.failures}`)
for (const r of results) {
  console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${(r.method ?? 'POST').padEnd(4)} ${r.path}  code=${r.code ?? '-'}  ${r.msg ?? r.error ?? ''}`)
}
console.log('\n关键样例：')
console.log(`  batch 返回: ${JSON.stringify(samples.batch)}`)
console.log(`  preview 路径: ${samples.preview?.pathLength} 个节点，首点 ${JSON.stringify(samples.preview?.first)}，末点 ${JSON.stringify(samples.preview?.last)}`)
console.log(`  新车任务移动: ${JSON.stringify(samples.newCarTask)}`)
console.log(`  服务端过滤: ${JSON.stringify(samples.filters)}`)
console.log(`  性能/CORS: ${JSON.stringify(samples.performance)} ${JSON.stringify(samples.cors)}`)
console.log(`\n明细已写入 ${OUT}/api-verify-v2-result.json`)
if (failures > 0) process.exitCode = 1
