package com.lizhi.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.domain.po.AgvTask;
import com.lizhi.domain.po.Node;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.mapper.AgvTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.math.RandomUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车辆仿真：由 stepJob 每个 tick 对每台车调用一次 {@link #step(String, TickContext)}。
 * <p>
 * 单车一步 = 持有该车的锁，在一个事务里完成"读最新状态 → 计算 → 只写发生变化的部分"。
 */
@Component
@Slf4j
public class AgvSimulator {

    /** 批量插入占用路径的分片大小，避免单条 SQL 超过 max_allowed_packet */
    private static final int PATH_BATCH_SIZE = 500;

    private final AgvUnlock agvUnlock;
    private final AgvPathMapper agvPathMapper;
    private final AgvMapper agvMapper;
    private final AgvTaskMapper agvTaskMapper;
    private final AgvLockManager agvLockManager;
    private final MapCache mapCache;

    /**
     * 每走多少格重新规划一次路径。1 = 每个 tick 都重规划（与改造前一致，默认）；
     * 大于 1 时沿已规划路径前进，走满 N 格、或下一格落在其它车辆的占用路径上、
     * 或车辆不在预期位置时才重规划。
     */
    private final int replanEveryCells;
    /** 脏检查开关：true = 状态没有变化就不写库；false = 每个 tick 都写（旧行为） */
    private final boolean dirtyCheck;

    /** 每台车"上次重规划以来走了几格"，仅 replanEveryCells &gt; 1 时使用；进程重启后丢失即触发一次重规划 */
    private final Map<String, ReplanState> replanStates = new ConcurrentHashMap<>();

    private static final class ReplanState {
        private final Integer taskId;
        private int cellsSinceReplan;

        private ReplanState(Integer taskId) {
            this.taskId = taskId;
        }
    }

    public AgvSimulator(AgvUnlock agvUnlock, AgvPathMapper agvPathMapper, AgvMapper agvMapper,
                        AgvTaskMapper agvTaskMapper, AgvLockManager agvLockManager, MapCache mapCache,
                        @Value("${agv.sim.replan-every-cells:1}") int replanEveryCells,
                        @Value("${agv.sim.dirty-check:true}") boolean dirtyCheck) {
        this.agvUnlock = agvUnlock;
        this.agvPathMapper = agvPathMapper;
        this.agvMapper = agvMapper;
        this.agvTaskMapper = agvTaskMapper;
        this.agvLockManager = agvLockManager;
        this.mapCache = mapCache;
        this.replanEveryCells = Math.max(1, replanEveryCells);
        this.dirtyCheck = dirtyCheck;
    }

    /**
     * 创建一辆小车（仅写库，不再为单车注册 1s 定时任务）。
     * 所有车辆统一由 stepJob 每秒从数据库读取并推进，避免"单车定时任务 + 全局 stepJob"双重写导致
     * batch 车辆被写回 idle@0,0、无法稳定执行任务的问题。
     */
    public void createAndStart(String agvCode){
        if (agvMapper.getAgv(agvCode) != null) {
            return;
        }
        AgvSnapshot car = AgvSnapshot.builder()
                .AgvCode(agvCode)
                .x(0)
                .y(0)
                .direction(RandomUtils.nextInt(360))
                .status("idle")
                .battery(100)
                .speed(0)
                .taskId(null)
                .plannedPath(null)
                .pathIndex(0)
                .lastUpdate(Instant.now().toString())
                .isCharge(0)
                .description("暂无")
                .build();
        agvMapper.insertAgv(car);
        log.info("{} 已创建，将由全局 stepJob 每秒推进模拟", agvCode);
    }

    /** 准备一轮 tick 的共享数据：地图快照取缓存，占用路径整轮只查一次 */
    public TickContext newTickContext() {
        return new TickContext(mapCache.current(), agvPathMapper.selectList(null));
    }

    /**
     * 单步更新一台车。
     * <p>
     * 进入时持有该车的锁并重新读取最新状态：startTask / stopAgv 等请求与 tick 写同一行 agv_snapshot，
     * 若用 tick 开始时的旧快照回写，会把刚下发的任务覆盖成 idle。车锁只串行化同一台车，
     * 不同车辆的写请求不再被整轮 tick 阻塞。
     */
    public void step(String agvCode, TickContext context) {
        if (agvCode == null || agvCode.isBlank()) {
            return;
        }
        agvLockManager.runInLockedTx(agvCode, () -> doStep(agvCode, context));
    }

    private void doStep(String agvCode, TickContext context) {
        AgvSnapshot car = agvMapper.getAgv(agvCode);
        if (car == null) {
            // 本轮开始后车辆已被删除，不再写回
            replanStates.remove(agvCode);
            return;
        }
        AgvSnapshot carBefore = new AgvSnapshot();
        BeanUtils.copyProperties(car, carBefore);

        AgvTask agvTask = null;
        if (car.getTaskId() != null) {
            agvTask = agvTaskMapper.getById(car.getTaskId());
        }
        if (agvTask == null) {
            agvTask = new AgvTask();
        }
        AgvTask taskBefore = new AgvTask();
        BeanUtils.copyProperties(agvTask, taskBefore);

        String status = car.getStatus() == null ? "" : car.getStatus();
        //模拟耗电
        if (car.getBattery() > 0 && !status.equals("idle")) {
            // 根据速度消耗电量，速度越快耗电越快，但降低耗电速度
            int speed = car.getSpeed() == null ? 0 : car.getSpeed();
            double batteryConsumption = Math.max(0.1, (double) speed / 1000);
            car.setBattery(Math.max(0, car.getBattery() - batteryConsumption));
        }
        // 到达充电站且处于充电任务中 → 开始充电
        if (Integer.valueOf(1).equals(car.getIsCharge()) && context.map() != null) {
            Node currentNode = new Node(car.getX(), car.getY());
            if (context.map().chargeNodes().contains(currentNode)) {
                if (!"charging".equals(status)) {
                    // 刚到站：行驶结束，清掉残留的占用路径点（旧实现会一直留在 agv_path 里）
                    clearPath(agvCode, context);
                }
                car.setStatus("charging");
                car.setSpeed(0);
                status = "charging";
            }
        }

        //状态
        switch (status) {
            case "idle" -> {
                // 空闲状态处理
                car.setSpeed(0);
                car.setPathIndex(0);
                car.setPlannedPath(null);
                car.setDescription("暂无");
                car.setTaskId(null);
                car.setIsCharge(0);
                replanStates.remove(agvCode);
            }
            case "running" -> moveAgv(car, agvTask, context);
            case "stopping" -> car.setSpeed(0);
            case "charging" -> {
                car.setBattery(Math.min(100, car.getBattery() + 5));
                agvTask.setDescription(agvTask.getAgvCode() + "正在" + agvTask.getDestination() + "充电");
                car.setDescription(agvTask.getDescription());
                if (car.getBattery() > 95) {
                    car.setStatus("idle");
                    car.setIsCharge(0);
                    agvTask.setStatus("completed");
                    agvTask.setDescription(agvTask.getAgvCode() + "已完成充电");
                    car.setDescription(agvTask.getDescription());
                    agvTask.setActualEndTime(LocalDateTime.now());
                }
            }
            default -> {
                // error 等其它状态：不推进
            }
        }

        // 脏检查：只写真正发生变化的行。空闲车每秒一次的空 UPDATE、运行车每秒一次的任务空 UPDATE 都省掉
        if (agvTask.getTaskId() != null && (!dirtyCheck || taskChanged(taskBefore, agvTask))) {
            agvTaskMapper.updateTask(agvTask);
        }
        car.setLastUpdate(carBefore.getLastUpdate());
        if (!dirtyCheck || !car.equals(carBefore)) {
            // lastUpdate 表示"状态最后一次变化的时间"，没有变化时不刷新
            car.setLastUpdate(Instant.now().toString());
            agvMapper.updateAgv(car);
        }
    }

    /** updateTask 只会写这四个字段，比较它们即可 */
    private static boolean taskChanged(AgvTask before, AgvTask after) {
        return !Objects.equals(before.getStatus(), after.getStatus())
                || !Objects.equals(before.getDescription(), after.getDescription())
                || !Objects.equals(before.getActualEndTime(), after.getActualEndTime())
                || !Objects.equals(before.getPassMidPoint(), after.getPassMidPoint());
    }

    /** agv小车根据路径移动 */
    private void moveAgv(AgvSnapshot car, AgvTask agvTask, TickContext context) {
        String agvCode = car.getAgvCode();
        Node currentNode = new Node(car.getX(), car.getY());
        // 起始点缺失时退化为车辆当前位置，避免空指针
        Integer startX = agvTask.getStartPointX();
        Integer startY = agvTask.getStartPointY();
        Node startNode = new Node(startX != null ? startX : car.getX(), startY != null ? startY : car.getY());
        List<Node> oldPath = car.getPlannedPath();
        if (oldPath == null || oldPath.isEmpty()) {
            // 路径丢失（例如历史上 /agv/report 未回传 plannedPath），终止任务并复位车辆
            failTask(car, agvTask, context, agvCode + " 规划路径丢失，任务终止");
            return;
        }
        Node endNode = oldPath.get(oldPath.size() - 1);

        boolean replan = needReplan(car, oldPath, context);
        if (currentNode.equals(startNode) && Integer.valueOf(1).equals(agvTask.getPassMidPoint())) {
            // 已到达取货起点：之后只需规划 起点 → 终点
            agvTask.setPassMidPoint(0);
            car.setPathIndex(0);
            replan = true;
        }

        List<Node> pathList;
        if (replan) {
            try {
                pathList = planPath(currentNode, startNode, endNode, agvTask, context, agvCode);
            } catch (NoPathFoundException e) {
                // 旧实现这里异常会直接中断整轮 tick，且每秒重复一次；现在只终止这一台车的任务
                failTask(car, agvTask, context, agvCode + "没有有效路径");
                return;
            }
            car.setPlannedPath(pathList);
            // 新路径从当前位置开始（下标 0 即当前位置），下一目标点最多是下标 1
            if (car.getPathIndex() > 1) {
                car.setPathIndex(1);
            }
            if (replanEveryCells > 1) {
                replanStates.put(agvCode, new ReplanState(car.getTaskId()));
            }
        } else {
            pathList = oldPath;
        }
        // 检查是否有规划路径
        if (pathList.isEmpty()) {
            failTask(car, agvTask, context, agvCode + "没有有效路径");
            return;
        }

        int pathIndex = Math.max(0, car.getPathIndex());
        if (pathIndex >= pathList.size()) {
            completeTask(car, agvTask, context);
            return;
        }

        Node targetNode = pathList.get(pathIndex);
        if (replan) {
            // 占用路径只在重规划时重写：1 次 delete + 1 次批量 insert
            savePath(pathList.subList(pathIndex, pathList.size()), agvCode, context);
        }
        // 计算当前位置与目标位置的距离
        int dx = targetNode.getX() - car.getX();
        int dy = targetNode.getY() - car.getY();

        // 如果已经到达目标位置
        if (dx == 0 && dy == 0) {
            // 已到达当前节点，指向下一个节点
            pathIndex += 1;
            car.setPathIndex(pathIndex);

            // 检查是否已完成所有路径点
            if (pathIndex >= pathList.size()) {
                completeTask(car, agvTask, context);
                return;
            }

            // 更新目标节点
            targetNode = pathList.get(pathIndex);
            // 重新计算距离
            dx = targetNode.getX() - car.getX();
            dy = targetNode.getY() - car.getY();
        }

        // 每次只移动一小步（例如每次移动1个单位）
        int moveStep = 1;
        if (dx != 0) {
            int moveX = Math.min(Math.abs(dx), moveStep) * (dx > 0 ? 1 : -1);
            car.setX(car.getX() + moveX);
        }
        if (dy != 0) {
            int moveY = Math.min(Math.abs(dy), moveStep) * (dy > 0 ? 1 : -1);
            car.setY(car.getY() + moveY);
        }
        ReplanState replanState = replanStates.get(agvCode);
        if (replanState != null) {
            replanState.cellsSinceReplan++;
        }

        // 计算到目标节点的方向
        if (dx > 0 && dy == 0) {
            car.setDirection(0);   // 向右
        } else if (dx < 0 && dy == 0) {
            car.setDirection(180); // 向左
        } else if (dx == 0 && dy > 0) {
            car.setDirection(90);  // 向下
        } else if (dx == 0 && dy < 0) {
            car.setDirection(270); // 向上
        } else if (dx > 0 && dy > 0) {
            car.setDirection(45);  // 右下
        } else if (dx > 0 && dy < 0) {
            car.setDirection(315); // 右上
        } else if (dx < 0 && dy > 0) {
            car.setDirection(135); // 左下
        } else if (dx < 0 && dy < 0) {
            car.setDirection(225); // 左上
        }
    }

    /**
     * 本 tick 是否需要重新规划。replanEveryCells = 1 时恒为 true（旧行为）。
     */
    private boolean needReplan(AgvSnapshot car, List<Node> path, TickContext context) {
        if (replanEveryCells <= 1) {
            return true;
        }
        String agvCode = car.getAgvCode();
        ReplanState state = replanStates.get(agvCode);
        if (state == null || !Objects.equals(state.taskId, car.getTaskId())
                || state.cellsSinceReplan >= replanEveryCells) {
            return true;
        }
        // 沿旧路径前进时，车辆应当正好停在 path[pathIndex] 上；不在（被 /agv/report 改过位置等）就重规划
        int pathIndex = car.getPathIndex();
        if (pathIndex < 0 || pathIndex >= path.size()) {
            return true;
        }
        Node expected = path.get(pathIndex);
        if (expected.getX() != car.getX() || expected.getY() != car.getY()) {
            return true;
        }
        // 下一格落在其它车辆的占用路径上 → 立即重规划
        if (pathIndex + 1 < path.size()) {
            Node next = path.get(pathIndex + 1);
            return context.isOccupiedByOthers(agvCode, next.getX(), next.getY());
        }
        return false;
    }

    /** 规划 当前位置 →（起点 →）终点 */
    private List<Node> planPath(Node currentNode, Node startNode, Node endNode, AgvTask agvTask,
                                TickContext context, String agvCode) throws NoPathFoundException {
        List<AgvPath> otherPaths = context.pathsOfOthers(agvCode);
        if (Integer.valueOf(1).equals(agvTask.getPassMidPoint())) {
            List<Node> pathList = new ArrayList<>();
            pathList.addAll(agvUnlock.realTimePath(context.map(), currentNode, startNode, otherPaths, agvCode, true));
            pathList.addAll(agvUnlock.realTimePath(context.map(), startNode, endNode, otherPaths, agvCode, true));
            return pathList;
        }
        return agvUnlock.realTimePath(context.map(), currentNode, endNode, otherPaths, agvCode, true);
    }

    /** 任务完成，回到空闲状态 */
    private void completeTask(AgvSnapshot car, AgvTask agvTask, TickContext context) {
        String agvCode = car.getAgvCode();
        agvTask.setStatus("completed");
        agvTask.setActualEndTime(LocalDateTime.now());
        agvTask.setDescription(agvCode + "已经到达目标点位");
        car.setStatus("idle");
        car.setDescription(agvTask.getDescription());
        clearPath(agvCode, context);
    }

    /** 任务失败：终止任务并把车辆复位为空闲 */
    private void failTask(AgvSnapshot car, AgvTask agvTask, TickContext context, String reason) {
        agvTask.setStatus("failed");
        agvTask.setActualEndTime(LocalDateTime.now());
        agvTask.setDescription(reason);
        car.setStatus("idle");
        car.setSpeed(0);
        car.setTaskId(null);
        car.setPlannedPath(null);
        car.setPathIndex(0);
        car.setIsCharge(0);
        clearPath(car.getAgvCode(), context);
        // 车辆的 taskId 已清空，doStep 末尾仍会按 agvTask.taskId 把失败状态写回任务
    }

    private void clearPath(String agvCode, TickContext context) {
        agvPathMapper.deleteByAgvCode(agvCode);
        context.replacePaths(agvCode, null);
        replanStates.remove(agvCode);
    }

    /**
     * 重写一台车的占用路径：1 次 delete + 分片批量 insert（旧实现是逐点 insert，约 50 条/车/秒）。
     */
    private void savePath(List<Node> pathNodes, String agvCode, TickContext context) {
        agvPathMapper.deleteByAgvCode(agvCode);
        Integer mapId = context.map() == null ? null : context.map().map().getMapId();
        List<AgvPath> paths = new ArrayList<>(pathNodes.size());
        for (Node node : pathNodes) {
            AgvPath path = new AgvPath();
            path.setX(node.getX());
            path.setY(node.getY());
            path.setType("path");
            path.setAgvCode(agvCode);
            path.setMapId(mapId);
            paths.add(path);
        }
        for (int from = 0; from < paths.size(); from += PATH_BATCH_SIZE) {
            agvPathMapper.insertBatch(paths.subList(from, Math.min(from + PATH_BATCH_SIZE, paths.size())));
        }
        context.replacePaths(agvCode, paths);
    }

    /** 外部调用：删除一辆车（数据库记录 + 占用路径） */
    public void remove(String agvId) {
        if (agvId == null || agvId.isBlank()) {
            return;
        }
        agvLockManager.runInLockedTx(agvId, () -> {
            agvMapper.delete(new QueryWrapper<AgvSnapshot>().eq("agv_code", agvId));
            agvPathMapper.deleteByAgvCode(agvId);
        });
        replanStates.remove(agvId);
    }
}
