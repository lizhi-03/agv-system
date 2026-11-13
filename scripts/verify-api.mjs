// AGV 后端接口实测脚本（真实 HTTP，经网关 http://localhost:8080/api）
// 用法：node verify-api.mjs
import { mkdirSync, writeFileSync } from 'node:fs'

const BASE = 'http://localhost:8080/api'
const OUT = './.verify'
mkdirSync(OUT, { recursive: true })

const results = []
let failures = 0

async function call(name, path, { method = 'POST', body } = {}) {
  const url = `${BASE}${path}`
  const started = Date.now()
  try {
    const res = await fetch(url, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(15000),
    })
    const text = await res.text()
    let json
    try {
      json = JSON.parse(text)
    } catch {
      throw new Error(`非 JSON 响应 (HTTP ${res.status}): ${text.slice(0, 200)}`)
    }
    const ok = res.ok && json.code === 200
    if (!ok) failures += 1
    results.push({ name, path, method, http: res.status, code: json.code, ok, ms: Date.now() - started, data: json.data, msg: json.msg })
    return json
  } catch (e) {
    failures += 1
    results.push({ name, path, method, ok: false, error: String(e.message || e) })
    return null
  }
}

function assert(cond, detail) {
  if (!cond) {
    failures += 1
    console.log(`  ✗ 断言失败: ${detail}`)
    return false
  }
  console.log(`  ✓ ${detail}`)
  return true
}

// 1. 地图
console.log('\n[1] 地图接口')
const mapRes = await call('当前地图', '/map/current', { method: 'GET' })
const map = mapRes?.data
assert(mapRes?.code === 200 && map != null, 'GET /map/current 返回 code=200 与地图数据')
assert(map?.width === 40 && map?.height === 30, `地图尺寸 40x30（实际 ${map?.width}x${map?.height}）`)
assert((map?.stations?.length ?? 0) === 8, `站点 8 个（实际 ${map?.stations?.length}）`)
assert((map?.obstacles?.length ?? 0) === 150, `障碍物 150 个（实际 ${map?.obstacles?.length}）`)
assert(Array.isArray(map?.paths), 'paths 为数组（占用路径）')
console.log(`  地图: ${map?.mapName} v${map?.version} ${map?.width}x${map?.height}`)

const stationRes = await call('站点列表', '/map/station', { method: 'GET' })
assert(stationRes?.code === 200 && Array.isArray(stationRes.data) && stationRes.data.length === 8, `GET /map/station 返回 8 个站点（实际 ${stationRes?.data?.length}）`)
assert(stationRes?.data?.some((s) => s.type === 'charge'), '站点列表含 charge 类型充电站')

const resetRes = await call('重置地图', '/map/reset', { method: 'POST' })
assert(resetRes?.code === 200 && resetRes.data?.mapId === map?.mapId, 'POST /map/reset 返回当前地图')

// 2. AGV
console.log('\n[2] AGV 接口')
const listRes = await call('车辆列表', '/agv/list', { method: 'POST' })
assert(listRes?.code === 200 && Array.isArray(listRes.data) && listRes.data.length === 3, `POST /agv/list 返回 3 台（实际 ${listRes?.data?.length}）`)
assert(listRes?.data?.every((a) => typeof a.agvCode === 'string'), '每台车含 agvCode 字段（JSON 命名正确）')

const getRes = await call('单车查询', '/agv/AGV001', { method: 'GET' })
assert(getRes?.code === 200 && getRes.data?.agvCode === 'AGV001', 'GET /agv/AGV001 返回正确车辆')
assert(typeof getRes?.data?.battery === 'number', '含 battery 数值')

const notFound = await call('单车查询-不存在', '/agv/AGV_NOT_EXIST', { method: 'GET' })
assert(notFound?.code === 204 && typeof notFound.msg === 'string', `GET 不存在的车返回 code=204 业务错误（实际 code=${notFound?.code} msg=${notFound?.msg}）`)

const batchRes = await call('批量加车', '/agv/batch/2', { method: 'POST' })
assert(batchRes?.code === 200, 'POST /agv/batch/2 成功')
await new Promise((r) => setTimeout(r, 2500))
const list2 = await call('车辆列表-加车后', '/agv/list', { method: 'POST' })
const codes = (list2?.data ?? []).map((a) => a.agvCode)
assert(codes.includes('AGVplus0001') && codes.includes('AGVplus0002'), `加车后出现 AGVplus0001/AGVplus0002（现有 ${codes.join(',')}）`)

const speedRes = await call('调速', '/agv/speed', { method: 'POST', body: { agvCode: 'AGV002', speed: 200 } })
assert(speedRes?.code === 200, 'POST /agv/speed 成功')
const afterSpeed = await call('单车查询-调速后', '/agv/AGV002', { method: 'GET' })
assert(afterSpeed?.data?.speed === 200, `AGV002 速度变为 200（实际 ${afterSpeed?.data?.speed}）`)
await call('调速-还原', '/agv/speed', { method: 'POST', body: { agvCode: 'AGV002', speed: 150 } })

const agv2 = afterSpeed?.data
const reportBody = {
  agvCode: 'AGV002',
  x: agv2?.x ?? 20,
  y: agv2?.y ?? 1,
  direction: agv2?.direction ?? 90,
  status: 'idle',
  battery: agv2?.battery ?? 87.5,
  speed: 150,
  taskId: null,
  plannedPath: null,
  isCharge: 0,
  description: '暂无',
}
const reportRes = await call('位置上报', '/agv/report', { method: 'POST', body: reportBody })
assert(reportRes?.code === 200, 'POST /agv/report 成功')

// 3. 任务（下发 / 暂停 / 恢复）
console.log('\n[3] 任务下发与车辆控制')
const pathRes = await call('创建任务', '/agv/path', {
  method: 'POST',
  body: {
    agvCode: 'AGV001',
    taskCode: `T${Date.now()}`,
    startPoint: { id: 'S2', x: 20, y: 2 },
    endPoint: { id: 'S3', x: 37, y: 2 },
    currentPoint: { x: 2, y: 1 },
  },
})
assert(pathRes?.code === 200 && Array.isArray(pathRes.data) && pathRes.data.length > 2, `POST /agv/path 返回规划路径（${pathRes?.data?.length} 个节点）`)
const plannedNodes = pathRes?.data ?? []
assert(plannedNodes.length > 0 && typeof plannedNodes[0].x === 'number', '路径节点含 x/y 坐标')

const stopRes = await call('暂停车辆', '/agv/stop', { method: 'POST', body: { agvCode: 'AGV001' } })
assert(stopRes?.code === 200, 'POST /agv/stop 成功')
await new Promise((r) => setTimeout(r, 1200))
const stopped = await call('单车查询-暂停后', '/agv/AGV001', { method: 'GET' })
assert(stopped?.data?.status === 'stopping' || stopped?.data?.status === 'paused', `暂停后状态为 stopping/paused（实际 ${stopped?.data?.status}）`)

const startRes = await call('恢复车辆', '/agv/start', { method: 'POST', body: { agvCode: 'AGV001' } })
assert(startRes?.code === 200, 'POST /agv/start 成功')
await new Promise((r) => setTimeout(r, 1200))
const running = await call('单车查询-恢复后', '/agv/AGV001', { method: 'GET' })
assert(running?.data?.status === 'running', `恢复后状态 running（实际 ${running?.data?.status}）`)
assert((running?.data?.plannedPath?.length ?? 0) > 0, '恢复后存在规划路径')

// 4. 充电
console.log('\n[4] 遥控充电')
const chargeRes = await call('遥控充电', '/agv/charge/AGV003', { method: 'POST' })
assert(chargeRes?.code === 200, 'POST /agv/charge/AGV003 成功')
await new Promise((r) => setTimeout(r, 1500))
const charged = await call('单车查询-充电后', '/agv/AGV003', { method: 'GET' })
assert(charged?.data?.status === 'running' || charged?.data?.status === 'charging' || charged?.data?.status === 'idle', `充电任务下发后状态合理（实际 ${charged?.data?.status}）`)
assert(charged?.data?.taskId != null || charged?.data?.status === 'idle', '充电任务已关联 taskId 或已完成')

// 5. 任务分页 / 取消 / 清空
console.log('\n[5] 任务分页、取消、清空')
const taskPage = await call('任务分页', '/task/page', { method: 'POST', body: { page: 1, pageSize: 10 } })
assert(taskPage?.code === 200 && typeof taskPage.data?.total === 'number' && Array.isArray(taskPage.data?.records), `POST /task/page 返回 total+records（total=${taskPage?.data?.total}）`)
const taskId = taskPage?.data?.records?.[0]?.taskId
assert(typeof taskId === 'number', `分页记录含 taskId（${taskId}）`)
if (typeof taskId === 'number') {
  const cancelRes = await call('取消任务', `/task/cancel/${taskId}`, { method: 'GET' })
  assert(cancelRes?.code === 200, `GET /task/cancel/${taskId} 成功`)
  await new Promise((r) => setTimeout(r, 1200))
  const afterCancel = await call('任务分页-取消后', '/task/page', { method: 'POST', body: { page: 1, pageSize: 10 } })
  const cancelled = (afterCancel?.data?.records ?? []).find((t) => t.taskId === taskId)
  assert(cancelled?.status === 'cancelled', `任务状态变为 cancelled（实际 ${cancelled?.status}）`)
}

const clearRes = await call('清空全部任务', '/agv/clear', { method: 'POST' })
assert(clearRes?.code === 200, 'POST /agv/clear 成功（空闲车辆不再触发空指针）')
await new Promise((r) => setTimeout(r, 1500))
const afterClear = await call('任务分页-清空后', '/task/page', { method: 'POST', body: { page: 1, pageSize: 20 } })
const activeCount = (afterClear?.data?.records ?? []).filter((t) => t.status === 'processing' || t.status === 'waiting' || t.status === 'paused').length
assert(activeCount === 0, `清空后无进行中任务（剩余 ${activeCount}）`)

// 6. 日志
console.log('\n[6] 系统日志')
const logPage = await call('日志分页', '/log/page', { method: 'POST', body: { page: 1, pageSize: 10 } })
assert(logPage?.code === 200 && typeof logPage.data?.total === 'number' && Array.isArray(logPage.data?.records), `POST /log/page 返回 total+records（total=${logPage?.data?.total}）`)
assert((logPage?.data?.records ?? []).length > 0, '日志记录非空')
assert(typeof logPage?.data?.records?.[0]?.message === 'string', '日志记录含 message')

// 7. 地图保存（生成新版本）
console.log('\n[7] 地图保存与版本链')
const currentMap = (await call('当前地图-保存前', '/map/current', { method: 'GET' }))?.data
const saveBody = {
  mapName: currentMap?.mapName,
  width: currentMap?.width,
  height: currentMap?.height,
  cellSize: currentMap?.cellSize,
  description: `${currentMap?.description}（接口验证新增一个站点）`,
  obstacles: currentMap?.obstacles ?? [],
  stations: [...(currentMap?.stations ?? []), { id: 'S7', type: 'station', x: 20, y: 15 }],
}
const saveRes = await call('保存地图', '/map/save', { method: 'POST', body: saveBody })
assert(saveRes?.code === 200, 'POST /map/save 成功')
const afterSave = (await call('当前地图-保存后', '/map/current', { method: 'GET' }))?.data
assert(afterSave?.version === (currentMap?.version ?? 0) + 1, `地图版本 +1（${currentMap?.version} → ${afterSave?.version}）`)
assert(afterSave?.mapId !== currentMap?.mapId, `生成新地图记录（mapId ${currentMap?.mapId} → ${afterSave?.mapId}）`)
assert((afterSave?.stations ?? []).some((s) => s.id === 'S7'), '新版本包含新增站点 S7')
const stationAfter = await call('站点列表-保存后', '/map/station', { method: 'GET' })
assert((stationAfter?.data ?? []).some((s) => s.id === 'S7'), 'GET /map/station 同步返回新站点')

// 汇总
const summary = {
  base: BASE,
  totalCalls: results.length,
  failures,
  passed: results.filter((r) => r.ok).length,
  failed: results.filter((r) => !r.ok),
}
writeFileSync(`${OUT}/api-verify-result.json`, JSON.stringify({ summary, results }, null, 2), 'utf8')

console.log('\n================ 汇总 ================')
console.log(`调用接口 ${summary.totalCalls} 次，成功 ${summary.passed}，失败 ${summary.failures}`)
for (const r of results) {
  console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.method.padEnd(4)} ${r.path}  code=${r.code ?? '-'}  ${r.msg ?? r.error ?? ''}`)
}
console.log(`\n明细已写入 ${OUT}/api-verify-result.json`)
if (failures > 0) process.exitCode = 1
