// 验证后端 P1/P2 修复：/agv/report 合并语义、plannedPath 反序列化、stop/start 业务错误、moveAgv NPE 防护、/map/save 校验
const BASE = 'http://localhost:8080/api'
let failures = 0
const ok = (cond, msg) => {
  console.log(`${cond ? '  ✓' : '  ✗'} ${msg}`)
  if (!cond) failures += 1
}
async function call(path, { method = 'POST', body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const text = await res.text()
  try {
    return { http: res.status, ...JSON.parse(text) }
  } catch {
    return { http: res.status, code: -1, raw: text }
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 选一台空闲车
const list = await call('/agv/list')
const idleCar = (list.data ?? []).find((a) => a.status === 'idle' && a.taskId == null)
ok(!!idleCar, `找到空闲车 ${idleCar?.agvCode}`)
const code = idleCar.agvCode

console.log('\n[1] /agv/report 合并语义（只传 agvCode/x/y 不清任务、不卡死）')
const pathRes = await call('/agv/path', {
  method: 'POST',
  body: {
    agvCode: code,
    taskCode: `FIX-${Date.now()}`,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S4', x: 2, y: 27 },
    currentPoint: { x: idleCar.x, y: idleCar.y },
  },
})
ok(pathRes.code === 200 && pathRes.data.length > 2, `创建任务成功，规划 ${pathRes.data?.length} 个节点`)
await sleep(1800)
const before = (await call(`/agv/${code}`, { method: 'GET' })).data
ok(before.taskId != null && (before.plannedPath?.length ?? 0) > 0, `上报前：taskId=${before.taskId}，路径 ${before.plannedPath?.length} 点`)

const partial = await call('/agv/report', { method: 'POST', body: { agvCode: code, x: before.x, y: before.y } })
ok(partial.http === 200 && partial.code === 200, `只传 {agvCode,x,y} 上报 → HTTP ${partial.http} / code=${partial.code}`)
const afterPartial = (await call(`/agv/${code}`, { method: 'GET' })).data
ok(afterPartial.taskId === before.taskId, `任务未被清空（taskId ${before.taskId} → ${afterPartial.taskId}）`)
ok((afterPartial.plannedPath?.length ?? 0) > 0, `plannedPath 未被清空（${afterPartial.plannedPath?.length} 点）`)
ok(afterPartial.battery === before.battery, `未传 battery 时保留原值（${afterPartial.battery}）`)
await sleep(2500)
const moving = (await call(`/agv/${code}`, { method: 'GET' })).data
ok(moving.x !== afterPartial.x || moving.y !== afterPartial.y || moving.status === 'idle', `车辆继续运行/移动（${afterPartial.x},${afterPartial.y} → ${moving.x},${moving.y}，status=${moving.status}）`)

console.log('\n[2] /agv/report 带非空 plannedPath（原 500 用例）')
const withPath = await call('/agv/report', {
  method: 'POST',
  body: {
    agvCode: code,
    x: moving.x,
    y: moving.y,
    direction: moving.direction,
    status: 'running',
    battery: moving.battery,
    speed: 150,
    taskId: moving.taskId,
    plannedPath: [{ x: moving.x, y: moving.y }, { x: moving.x, y: moving.y + 1 }],
    isCharge: 0,
    description: '上报测试',
  },
})
ok(withPath.http === 200 && withPath.code === 200, `带 plannedPath 上报不再 500（HTTP ${withPath.http} / code=${withPath.code}）`)
const afterPath = (await call(`/agv/${code}`, { method: 'GET' })).data
ok((afterPath.plannedPath?.length ?? 0) === 2, `plannedPath 按请求更新（${afterPath.plannedPath?.length} 点）`)
// 收尾：清掉这台车的任务，避免污染
await call('/agv/clear')

console.log('\n[3] 无任务车调用 /agv/stop 与 /agv/start')
const idleList = (await call('/agv/list')).data
const freeCar = idleList.find((a) => a.taskId == null)
ok(!!freeCar, `找到无任务车 ${freeCar?.agvCode}`)
const stopRes = await call('/agv/stop', { method: 'POST', body: { agvCode: freeCar.agvCode } })
ok(stopRes.http === 200 && stopRes.code === 204 && !!stopRes.msg, `无任务车 stop → HTTP ${stopRes.http} / code=${stopRes.code} / msg=${stopRes.msg}`)
const startRes = await call('/agv/start', { method: 'POST', body: { agvCode: freeCar.agvCode } })
ok(startRes.http === 200 && startRes.code === 204 && !!startRes.msg, `无任务车 start → HTTP ${startRes.http} / code=${startRes.code} / msg=${startRes.msg}`)

console.log('\n[4] /map/save 入参校验（缺 width/height/obstacles 时继承旧图）')
const mapBefore = (await call('/map/current', { method: 'GET' })).data
const partialSave = await call('/map/save', {
  method: 'POST',
  body: { stations: [...(mapBefore.stations ?? []), { id: 'S8', type: 'station', x: 39, y: 29 }] },
})
ok(partialSave.code === 200, `只传 stations 保存 → code=${partialSave.code}（原会写入 NULL）`)
const mapAfter = (await call('/map/current', { method: 'GET' })).data
ok(mapAfter.version === mapBefore.version + 1, `版本 +1（${mapBefore.version} → ${mapAfter.version}）`)
ok(mapAfter.width === mapBefore.width && mapAfter.height === mapBefore.height, `宽高继承旧图（${mapAfter.width}×${mapAfter.height}）`)
ok((mapAfter.obstacles?.length ?? 0) === (mapBefore.obstacles?.length ?? 0), `障碍物继承旧图（${mapAfter.obstacles?.length} 个）`)
ok((mapAfter.stations ?? []).length === (mapBefore.stations ?? []).length + 1, '新增站点已写入')

console.log('\n[5] /agv/path 参数校验')
const badPath = await call('/agv/path', { method: 'POST', body: { agvCode: 'AGV001' } })
ok(badPath.code === 204 && !!badPath.msg, `缺少起终点 → code=${badPath.code} / msg=${badPath.msg}`)

console.log(`\n结果：${failures === 0 ? '全部通过' : failures + ' 项失败'}`)
if (failures > 0) process.exitCode = 1
