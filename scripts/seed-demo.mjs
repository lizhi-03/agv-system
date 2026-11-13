// 交付演示数据：在干净种子库上通过真实接口创建 3 条任务（2 条运输 + 1 条充电），
// 让交付态呈现"运行中的 AGV + 真实规划路径 + processing 任务"。
// 用法：node scripts/seed-demo.mjs
const BASE = 'http://localhost:8080/api'

async function call(path, { method = 'POST', body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const json = await res.json().catch(() => ({}))
  return { http: res.status, ...json }
}

const tasks = [
  {
    agvCode: 'AGV001',
    taskCode: `DEMO-${Date.now()}-1`,
    startPoint: { id: 'S1', x: 2, y: 2 },
    endPoint: { id: 'S6', x: 37, y: 27 },
    currentPoint: { x: 2, y: 1 },
  },
  {
    agvCode: 'AGV002',
    taskCode: `DEMO-${Date.now()}-2`,
    startPoint: { id: 'S2', x: 20, y: 2 },
    endPoint: { id: 'S4', x: 2, y: 27 },
    currentPoint: { x: 20, y: 1 },
  },
]

for (const task of tasks) {
  const result = await call('/agv/path', { body: task })
  const points = Array.isArray(result.data) ? result.data.length : 0
  console.log(`${task.agvCode}: ${task.startPoint.id} → ${task.endPoint.id}  code=${result.code} 路径点=${points} ${result.msg ?? ''}`)
}

const charge = await call('/agv/charge/AGV003')
console.log(`AGV003: 遥控充电  code=${charge.code} ${charge.msg ?? ''}`)

const cars = await call('/agv/list')
console.log('\n当前车辆：')
for (const car of cars.data ?? []) {
  console.log(`  ${car.agvCode}  ${car.status}  (${car.x},${car.y})  电量 ${Math.round(car.battery)}%  任务 ${car.taskId ?? '—'}`)
}
const page = await call('/task/page', { body: { page: 1, pageSize: 5 } })
console.log(`任务总数：${page.data?.total}，最新：${(page.data?.records ?? []).map((t) => `${t.taskId}:${t.status}`).join(', ')}`)
