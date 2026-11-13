package com.lizhi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lizhi.astart.map.GridMap;
import com.lizhi.astart.service.AStarSolver;
import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.dto.AgvSnapshotDTO;
import com.lizhi.domain.dto.AgvTaskDTO;
import com.lizhi.domain.po.*;
import com.lizhi.feign.AgvFeignClient;
import com.lizhi.mapper.*;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.service.AgvService;
import com.lizhi.service.AgvTaskService;
import com.lizhi.utils.AgvConversion;
import com.lizhi.utils.AgvSimulator;
import com.lizhi.utils.AgvUnlock;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import org.springframework.beans.BeanUtils;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@Slf4j
public class AgvServiceImpl implements AgvService {

    private final AgvMapMapper agvMapMapper;
    private final AgvSimulator agvSimulator;
    private final AgvMapper agvMapper;
    private final AgvTaskMapper agvTaskMapper;
    private final AgvConversion agvConversion;
    private final AgvTaskService agvTaskService;
    private final AgvUnlock agvUnlock;
    private final AgvPathMapper agvPathMapper;
    private final AgvLogMapper agvLogMapper;
    private final LiveStateCache liveStateCache;


    public AgvServiceImpl(AgvMapMapper agvMapMapper, AgvSimulator agvSimulator, AgvMapper agvMapper, AgvTaskMapper agvTaskMapper, DiscoveryClient discoveryClient, AgvFeignClient agvFeignClient, AgvConversion agvConversion, AgvTaskServiceImpl agvTaskServiceImpl, AgvTaskService agvTaskService, AgvUnlock agvUnlock, AgvPathMapper agvPathMapper, AgvLogMapper agvLogMapper, LiveStateCache liveStateCache) {
        this.agvMapMapper = agvMapMapper;
        this.agvSimulator = agvSimulator;
        this.agvMapper = agvMapper;
        this.agvTaskMapper = agvTaskMapper;
        this.agvConversion = agvConversion;
        this.agvTaskService = agvTaskService;
        this.agvUnlock = agvUnlock;
        this.agvPathMapper = agvPathMapper;
        this.agvLogMapper = agvLogMapper;
        this.liveStateCache = liveStateCache;
    }

    /** 批量加车编号格式：AGVplus + 至少 4 位数字 */
    private static final Pattern AGV_PLUS_PATTERN = Pattern.compile("^AGVplus(\\d+)$");

    /**
     * 进行agv任务
     * @param agvTaskDTO
     * @return
     * @throws NoPathFoundException
     */
    @Override
    public List<Node> startTask(AgvTaskDTO agvTaskDTO) throws NoPathFoundException{
        if (agvTaskDTO == null) {
            throw new IllegalArgumentException("任务参数不能为空");
        }
        String agvCode = agvTaskDTO.getAgvCode();
        if (agvCode == null || agvCode.isBlank()) {
            throw new IllegalArgumentException("agvCode 不能为空");
        }
        AgvTaskDTO.Point startPoint = agvTaskDTO.getStartPoint();
        AgvTaskDTO.Point endPoint = agvTaskDTO.getEndPoint();
        validatePoint(startPoint, "起点 startPoint");
        validatePoint(endPoint, "终点 endPoint");
        // 获取AGV当前状态
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        if (agv == null) {
            throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
        }
        AgvTaskDTO.Point currentPoint = agvTaskDTO.getCurrentPoint();
        if (currentPoint == null) {
            // 未传当前位置时按车辆库中位置计算路径
            currentPoint = pointOf(agv.getX(), agv.getY());
        } else {
            validatePoint(currentPoint, "当前位置 currentPoint");
        }
        // 先只做路径规划（只读），避免在持有车辆状态锁时执行耗时的 A*
        List<Node> pathList = planPath(agvCode, currentPoint, startPoint, endPoint, true);
        if (pathList.isEmpty()) {
            throw new IllegalArgumentException("未找到有效路径");
        }
        String startLabel = labelOf(startPoint);
        String endLabel = labelOf(endPoint);
        AgvTask agvTask = new AgvTask();
        String taskCode = agvTaskDTO.getTaskCode();
        if (taskCode == null || taskCode.isBlank()) {
            taskCode = "T" + System.currentTimeMillis() + agvCode;
        }
        agvTask.setTaskCode(taskCode);
        agvTask.setAgvCode(agvCode);
        agvTask.setTaskType("物料运输");
        agvTask.setStatus("processing");
        agvTask.setPriority("low");
        agvTask.setSource(startPoint.getId());
        agvTask.setDestination(endPoint.getId());
        agvTask.setStartTime(LocalDateTime.now());
        agvTask.setStartPointX(startPoint.getX());
        agvTask.setStartPointY(startPoint.getY());
        agvTask.setPassMidPoint(samePosition(currentPoint, startPoint) ? 0 : 1);
        agvTask.setDescription("正在从" + startLabel + "前往" + endLabel);
        // 任务下发与每秒一次的 stepJob 使用同一把车辆状态锁，防止模拟器用旧快照回写、
        // 把刚下发的任务覆盖成 idle（batch 新车表现为停在 0,0 不执行任务）。
        synchronized (agvSimulator) {
            AgvSnapshot latest = agvMapper.getAgv(agvCode);
            if (latest == null) {
                throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
            }
            agvTaskMapper.insert(agvTask);
            latest.setPlannedPath(pathList);
            latest.setPathIndex(0);
            latest.setStatus("running");
            latest.setSpeed(150);
            latest.setTaskId(agvTask.getTaskId());
            latest.setDescription(agvTask.getDescription());
            agvMapper.updateAgv(latest);
            AgvLog agvLog = new AgvLog();
            agvLog.setTimestamp(LocalDateTime.now());
            agvLog.setLevel("info");
            agvLog.setMessage(agvCode + " 开始执行任务 " + agvTask.getTaskId());
            agvLogMapper.insert(agvLog);
        }
        // 写后立即刷新实时快照，保证 /agv/live、/agv/list 立刻能看到 taskId/status 变化
        liveStateCache.rebuild();
        return pathList;
    }

    /**
     * 只预览任务路径：不写任务、不改车辆状态、不写占用路径，供前端地图选点向导使用。
     */
    @Override
    public List<Node> previewTask(AgvTaskDTO agvTaskDTO) throws NoPathFoundException {
        if (agvTaskDTO == null) {
            throw new IllegalArgumentException("预览参数不能为空");
        }
        String agvCode = agvTaskDTO.getAgvCode();
        if (agvCode == null || agvCode.isBlank()) {
            throw new IllegalArgumentException("agvCode 不能为空");
        }
        AgvTaskDTO.Point startPoint = agvTaskDTO.getStartPoint();
        AgvTaskDTO.Point endPoint = agvTaskDTO.getEndPoint();
        validatePoint(startPoint, "起点 startPoint");
        validatePoint(endPoint, "终点 endPoint");
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        if (agv == null) {
            throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
        }
        AgvTaskDTO.Point currentPoint = agvTaskDTO.getCurrentPoint();
        if (currentPoint == null) {
            currentPoint = pointOf(agv.getX(), agv.getY());
        } else {
            validatePoint(currentPoint, "当前位置 currentPoint");
        }
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        if (currentMap == null) {
            throw new IllegalStateException("当前没有可用地图，无法预览路径");
        }
        validateInMap(currentMap, currentPoint, "当前位置 currentPoint");
        validateInMap(currentMap, startPoint, "起点 startPoint");
        validateInMap(currentMap, endPoint, "终点 endPoint");
        return planPath(agvCode, currentPoint, startPoint, endPoint, false);
    }

    /**
     * 规划“当前位置 → 起点 → 终点”的路径。current 与 start 重合时只规划起点到终点。
     *
     * @param logOnError 规划失败时是否写 agv_log；/agv/preview 传 false 保证零写入
     */
    private List<Node> planPath(String agvCode, AgvTaskDTO.Point currentPoint, AgvTaskDTO.Point startPoint,
                                AgvTaskDTO.Point endPoint, boolean logOnError) throws NoPathFoundException {
        Node currentNode = new Node(currentPoint.getX(), currentPoint.getY());
        Node startNode = new Node(startPoint.getX(), startPoint.getY());
        Node endNode = new Node(endPoint.getX(), endPoint.getY());
        ArrayList<Node> pathList = new ArrayList<>();
        if (currentNode.equals(startNode)) {
            // 车辆已在开始站点，直接规划起点到终点
            pathList.addAll(agvUnlock.realTimePath(startNode, endNode, null, agvCode, logOnError));
        } else {
            pathList.addAll(agvUnlock.realTimePath(currentNode, startNode, null, agvCode, logOnError));
            pathList.addAll(agvUnlock.realTimePath(startNode, endNode, null, agvCode, logOnError));
        }
        return pathList;
    }

    /** 校验点位必填坐标 */
    private static void validatePoint(AgvTaskDTO.Point point, String name) {
        if (point == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (point.getX() == null || point.getY() == null) {
            throw new IllegalArgumentException(name + " 的坐标 x/y 不能为空");
        }
    }

    /** 校验点位在地图范围内 */
    private static void validateInMap(AgvMap map, AgvTaskDTO.Point point, String name) {
        Integer width = map.getWidth();
        Integer height = map.getHeight();
        if (width == null || height == null) {
            return;
        }
        if (point.getX() < 0 || point.getX() >= width || point.getY() < 0 || point.getY() >= height) {
            throw new IllegalArgumentException(name + " 坐标(" + point.getX() + "," + point.getY()
                    + ")超出地图范围 " + width + "x" + height);
        }
    }

    /** 由坐标构造点位 */
    private static AgvTaskDTO.Point pointOf(Integer x, Integer y) {
        AgvTaskDTO.Point point = new AgvTaskDTO.Point();
        point.setX(x);
        point.setY(y);
        return point;
    }

    /** 两个点位是否为同一坐标 */
    private static boolean samePosition(AgvTaskDTO.Point a, AgvTaskDTO.Point b) {
        return a.getX().equals(b.getX()) && a.getY().equals(b.getY());
    }

    /** 点位展示名：优先站点 id，否则用坐标 */
    private static String labelOf(AgvTaskDTO.Point point) {
        if (point.getId() != null && !point.getId().isBlank()) {
            return point.getId();
        }
        return "(" + point.getX() + "," + point.getY() + ")";
    }

    /**
     * 获取所有小车状态（V2.1：直接读每秒重建的内存快照，客户端请求不再打 DB）
     * @return
     */
    @Override
    public List<AgvSnapshot> getAllAgv() throws NoPathFoundException {
        return liveStateCache.getCars();
    }

    /**
     * 获取agv小车
     * @param agvCode
     * @return
     */
    @Override
    public AgvSnapshot getAgv(String agvCode) {
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        // 车辆不存在时返回 null，由 Controller 统一返回业务错误，避免空指针 500
        if (agv == null) {
            return null;
        }
        LambdaQueryWrapper<AgvPath> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(AgvPath::getAgvCode,agvCode);
        List<AgvPath> agvPaths = agvPathMapper.selectList(lambdaQueryWrapper);
        agv.setAgvPaths(agvPaths);
        return agv;
    }

    /**
     * 记录小车当前位置
     * @param agvSnapshotDTO
     */
    @Override
    public void reportAgv(AgvSnapshotDTO agvSnapshotDTO) {
        String agvCode = requireAgvCode(agvSnapshotDTO);
        agvSnapshotDTO.setLastUpdate(Instant.now().toString());
        // 合并语义：只覆盖请求里显式提供的字段。
        // 否则 /agv/report 只上报 x/y 时会把 task_id、planned_path 置空，导致运行中的车辆任务丢失并卡死。
        AgvSnapshot current = agvMapper.getAgv(agvCode);
        if (current != null) {
            if (agvSnapshotDTO.getX() == null) agvSnapshotDTO.setX(current.getX());
            if (agvSnapshotDTO.getY() == null) agvSnapshotDTO.setY(current.getY());
            if (agvSnapshotDTO.getDirection() == null) agvSnapshotDTO.setDirection(current.getDirection());
            if (agvSnapshotDTO.getStatus() == null) agvSnapshotDTO.setStatus(current.getStatus());
            if (agvSnapshotDTO.getBattery() <= 0 && current.getBattery() > 0) {
                agvSnapshotDTO.setBattery(current.getBattery());
            }
            if (agvSnapshotDTO.getSpeed() == null) agvSnapshotDTO.setSpeed(current.getSpeed());
            if (agvSnapshotDTO.getTaskId() == null) agvSnapshotDTO.setTaskId(current.getTaskId());
            if (agvSnapshotDTO.getPlannedPath() == null) agvSnapshotDTO.setPlannedPath(current.getPlannedPath());
            if (agvSnapshotDTO.getDescription() == null) agvSnapshotDTO.setDescription(current.getDescription());
            if (agvSnapshotDTO.getIsCharge() == null) agvSnapshotDTO.setIsCharge(current.getIsCharge());
        }
        agvMapper.updateAgv(agvSnapshotDTO);
        // 写后立即刷新实时快照（R-V2-08）
        liveStateCache.rebuild();
    }

    /** 校验并取出 agvCode */
    private String requireAgvCode(AgvSnapshotDTO dto) {
        if (dto == null || dto.getAgvCode() == null || dto.getAgvCode().isBlank()) {
            throw new IllegalArgumentException("agvCode 不能为空");
        }
        return dto.getAgvCode();
    }

    /**
     * 动态加车（V2）：
     * <ul>
     *   <li>扫描现有 AGVplusNNNN 的最大编号，从 +1 开始连续编号，重复调用不会重复注册；</li>
     *   <li>数量校验 1..50，非法抛 IllegalArgumentException → 统一返回 code=204 + msg；</li>
     *   <li>仅写 agv_snapshot（不再注册单车定时任务），车辆由全局 stepJob 每秒推进，立即可用。</li>
     * </ul>
     *
     * @param mun 本次新增车辆数量
     * @return 新建车辆编号列表
     */
    @Override
    public synchronized List<String> batchAgv(Integer mun) {
        if (mun == null || mun < 1 || mun > 50) {
            throw new IllegalArgumentException("批量加车数量必须在 1~50 之间");
        }
        int maxIndex = 0;
        for (AgvSnapshot agv : agvMapper.getAllAgv()) {
            String agvCode = agv.getAgvCode();
            if (agvCode == null) {
                continue;
            }
            Matcher matcher = AGV_PLUS_PATTERN.matcher(agvCode);
            if (matcher.matches()) {
                try {
                    maxIndex = Math.max(maxIndex, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignore) {
                    // 编号超出 int 范围时忽略该车，按其它车辆继续编号
                }
            }
        }
        List<String> created = new ArrayList<>(mun);
        for (int i = 1; i <= mun; i++) {
            String agvCode = "AGVplus" + String.format("%04d", maxIndex + i);
            agvSimulator.createAndStart(agvCode);
            created.add(agvCode);
        }
        // 写后立即刷新实时快照，保证 /agv/live 立刻出现新车
        liveStateCache.rebuild();
        return created;
    }

    /**
     * 遥控小车充电
     * @param agvCode
     */
    public void chargeAgv(String agvCode) throws NoPathFoundException {
        //导航到最近的充电点位
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        if (currentMap == null) {
            throw new IllegalStateException("当前没有可用地图，无法规划充电路径");
        }
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        if (agv == null) {
            throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
        }
        Integer taskId = agv.getTaskId();
        if (taskId != null){
            agvTaskService.cancelTask(agv.getTaskId());
        }
        Node startNode = new Node(agv.getX(), agv.getY());
        AgvTask agvTask = new AgvTask();
        agvTask.setTaskCode("T" + System.currentTimeMillis() + agv.getAgvCode());
        agvTask.setAgvCode(agvCode);
        agvTask.setTaskType("充电");
        agvTask.setStatus("processing");
        agvTask.setPassMidPoint(0);
        agvTask.setStartPointX(agv.getX());
        agvTask.setStartPointY(agv.getY());
        if(agv.getBattery() <= 30){
            agvTask.setPriority("high");
        }else {
            agvTask.setPriority("low");
        }

        agvTask.setStartTime(LocalDateTime.now());
        // 修复：处理 LinkedHashMap 转换问题
        List<AgvMap.Station> oldStations = currentMap.getStations();
        List<AgvMap.Station> stations = agvConversion.convertStationsToAgvStationObjects(oldStations);
        ArrayList<List<Node>> pathList = new ArrayList<>();
        String stationId = null;
        for (AgvMap.Station station : stations) {
            if ("charge".equals(station.getType())) {  // 修复：使用 equals 比较字符串
                Node charge = new Node(station.getX(), station.getY());
                stationId = station.getId();
                pathList.add(agvUnlock.realTimePath(startNode,charge,null,agvCode));
            }
        }
        agvTask.setDestination(stationId);
        agvTask.setDescription(agvCode + "前往" + stationId + "充电");


        List<Node> shortestPath = pathList.stream()
                .min(Comparator.comparingInt(List::size))
                .orElse(null); // 如果列表为空则返回null
        if (shortestPath == null || shortestPath.isEmpty()) {
            throw new IllegalStateException("当前地图没有可用充电站，或车辆无法到达充电站");
        }

        agvTaskMapper.insert(agvTask);
        agv.setPlannedPath(shortestPath);
        agv.setPathIndex(0);
        agv.setStatus("running");
        agv.setIsCharge(1);
        agv.setDescription(agvTask.getDescription());
        agv.setTaskId(agvTask.getTaskId());
        AgvLog agvLog = new AgvLog();
        agvLog.setTimestamp(LocalDateTime.now());
        agvLog.setLevel("warning");
        agvLog.setMessage(agvCode + " 电量低，自动前往充电站");
        agvLogMapper.insert(agvLog);
        // 到达充电点位后
        agvMapper.updateAgv(agv);
        liveStateCache.rebuild();
    }

    /**
     * 停止小车运行
     * @param agvSnapshotDTO
     */
    @Override
    public void stopAgv(AgvSnapshotDTO agvSnapshotDTO) {
        String agvCode = requireAgvCode(agvSnapshotDTO);
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        if (agv == null) {
            throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
        }
        if (agv.getTaskId() == null) {
            throw new IllegalStateException("该车辆当前没有任务，无法暂停");
        }
        AgvTask agvTask = agvTaskMapper.getById(agv.getTaskId());
        if (agvTask == null) {
            throw new IllegalStateException("该车辆关联的任务不存在，无法暂停");
        }
        agvTask.setStatus("paused");
        agv.setStatus("stopping");
        agvTaskMapper.updateTask(agvTask);
        agvMapper.updateAgv(agv);
        liveStateCache.rebuild();
    }

    /**
     * 启动agv小车运动
     * @param agvSnapshotDTO
     */
    @Override
    public void startAgv(AgvSnapshotDTO agvSnapshotDTO) {
        String agvCode = requireAgvCode(agvSnapshotDTO);
        AgvSnapshot agv = agvMapper.getAgv(agvCode);
        if (agv == null) {
            throw new IllegalArgumentException("未找到AGV小车: " + agvCode);
        }
        if (agv.getTaskId() == null) {
            throw new IllegalStateException("该车辆当前没有任务，无法启动");
        }
        AgvTask agvTask = agvTaskMapper.getById(agv.getTaskId());
        if (agvTask == null) {
            throw new IllegalStateException("该车辆关联的任务不存在，无法启动");
        }
        agv.setStatus("running");
        agvTask.setStatus("processing");
        agv.setSpeed(150);
        agvTaskMapper.updateTask(agvTask);
        agvMapper.updateAgv(agv);
        liveStateCache.rebuild();
    }

    /**
     * 设置agv小车速度
     * @param agvSnapshotDTO
     */
    @Override
    public void setSpeed(AgvSnapshotDTO agvSnapshotDTO) {
        String agvCode = requireAgvCode(agvSnapshotDTO);
        Integer speed = agvSnapshotDTO.getSpeed();
        if (speed == null) {
            throw new IllegalArgumentException("speed 不能为空");
        }
        if (speed < 0 || speed > 1000) {
            throw new IllegalArgumentException("speed 取值范围为 0~1000 cm/s");
        }
        AgvSnapshot agvSnapshot = new AgvSnapshot();
        agvSnapshot.setAgvCode(agvCode);
        agvSnapshot.setSpeed(speed);
        agvMapper.updateSpeed(agvSnapshot);
        liveStateCache.rebuild();
    }

    /**
     * 清除所有任务：车辆立即复位为 idle，并清空 taskId/plannedPath/占用路径，
     * 保证写后立刻读 /agv/live 看到完整的新状态（R-V2-08）。
     */
    @Override
    public void clearAllTask() {
        List<AgvSnapshot> allAgv = agvMapper.getAllAgv();
        for (AgvSnapshot agvSnapshot : allAgv) {
            agvSnapshot.setStatus("idle");
            // 空闲车辆没有任务，跳过任务状态更新，避免空指针
            if (agvSnapshot.getTaskId() != null) {
                AgvTask agvTask = agvTaskMapper.getById(agvSnapshot.getTaskId());
                if (agvTask != null) {
                    agvTask.setStatus("cancelled");
                    agvTaskMapper.updateTask(agvTask);
                }
            }
            agvSnapshot.setTaskId(null);
            agvSnapshot.setPlannedPath(null);
            agvSnapshot.setPathIndex(0);
            agvSnapshot.setSpeed(0);
            agvSnapshot.setIsCharge(0);
            agvMapper.updateAgv(agvSnapshot);
            agvPathMapper.deleteByAgvCode(agvSnapshot.getAgvCode());
        }
        liveStateCache.rebuild();
    }
}
