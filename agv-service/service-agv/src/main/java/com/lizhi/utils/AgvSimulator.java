package com.lizhi.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.po.*;
import com.lizhi.mapper.AgvMapMapper;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.mapper.AgvTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.math.RandomUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class AgvSimulator {

    private final AgvConversion agvConversion;
    private final AgvUnlock agvUnlock;
    private final AgvMapMapper agvMapMapper;
    private final AgvPathMapper agvPathMapper;
    private final AgvMapper agvMapper;
    private final AgvTaskMapper agvTaskMapper;

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

    /**
     * 获取充电点位
     * @return
     */
    private ArrayList<Node> getChargeStation(){
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        List<AgvMap.Station> stations = currentMap.getStations();
        ArrayList<Node> chargeList = new ArrayList<>();

        // 修复：处理 LinkedHashMap 转换问题
        List<AgvMap.Station> processedStations = new ArrayList<>();
        for (Object obj : stations) {
            if (obj instanceof LinkedHashMap) {
                // 手动转换 LinkedHashMap 到 Station 对象
                LinkedHashMap<String, Object> map = (LinkedHashMap<String, Object>) obj;
                AgvMap.Station station = new AgvMap.Station();
                station.setX(((Number) map.get("x")).intValue());
                station.setY(((Number) map.get("y")).intValue());
                station.setType((String) map.get("type"));
                processedStations.add(station);
            } else if (obj instanceof AgvMap.Station) {
                processedStations.add((AgvMap.Station) obj);
            }
        }

        for (AgvMap.Station station : processedStations) {
            if ("charge".equals(station.getType())) {
                Node charge = new Node(station.getX(), station.getY());
                chargeList.add(charge);
            }
        }
        return chargeList;
    }

    /**
     * 单步更新。
     * <p>
     * 方法加锁并且进入时重新从数据库读取车辆最新状态：startTask / stopAgv 等请求与每秒一次的
     * stepJob 写同一行 agv_snapshot，若用 tick 开始时的旧快照回写，可能把刚下发的任务覆盖成
     * idle@旧坐标（batch 新车表现为“加了车却永远停在 0,0 不执行任务”）。这里用同一把锁串行化
     * “读-算-写”，保证任务不会被模拟器写回。
     */
    public synchronized void step(AgvSnapshot car) throws NoPathFoundException {
        if (car == null || car.getAgvCode() == null) {
            return;
        }
        AgvSnapshot latest = agvMapper.getAgv(car.getAgvCode());
        if (latest != null) {
            car = latest;
        }
        Integer taskId = car.getTaskId();
        AgvTask agvTask = new AgvTask();
        if (taskId != null){
            agvTask = agvTaskMapper.getById(taskId);
        }
        //模拟耗电
        if(car.getBattery() > 0 && !car.getStatus().equals("idle")){
            // 根据速度消耗电量，速度越快耗电越快，但降低耗电速度
            double batteryConsumption = Math.max(0.1, (double) car.getSpeed() / 1000);
            car.setBattery(Math.max(0, car.getBattery() - batteryConsumption));
        }
        ArrayList<Node> chargeStations = getChargeStation();
        Node currentNode = new Node(car.getX(), car.getY());
        for (Node chargeStation : chargeStations) {
            if (chargeStation.equals(currentNode) && car.getIsCharge() == 1){
                car.setStatus("charging");
                car.setSpeed(0);
            }
        }

        //状态
        switch (car.getStatus()){
            case "idle" ->{
                // 空闲状态处理
                car.setSpeed(0);
                car.setPathIndex(0);
                car.setPlannedPath(null);
                car.setDescription("暂无");
                car.setTaskId(null);
                car.setIsCharge(0);
            }
            case "running" -> moveAgv(car,agvTask);
            case "stopping" ->{
                car.setSpeed(0);
                car.setLastUpdate(Instant.now().toString());
            }
            case "charging"->{
                car.setBattery(Math.min(100,car.getBattery() +5));
                agvTask.setDescription(agvTask.getAgvCode()+"正在"+agvTask.getDestination()+"充电");
                car.setDescription(agvTask.getDescription());
                if(car.getBattery() > 95){
                    car.setStatus("idle");
                    car.setIsCharge(0);
                    agvTask.setStatus("completed");
                    agvTask.setDescription(agvTask.getAgvCode()+"已完成充电");
                    car.setDescription(agvTask.getDescription());
                    agvTask.setActualEndTime(LocalDateTime.now());
                }
                car.setLastUpdate(Instant.now().toString());
            }
        }
        String agvCode = car.getAgvCode();
        if (car.getTaskId() != null){
            agvTaskMapper.updateTask(agvTask);
        }
        if (agvMapper.getAgv(agvCode) == null){
            agvMapper.insertAgv(car);
        }else {
            agvMapper.updateAgv(car);
        }
    }

    /** agv小车根据路径移动 */
    public void moveAgv(AgvSnapshot car,AgvTask agvTask) throws NoPathFoundException {
        String agvCode = car.getAgvCode();
        Node currentNode = new Node(car.getX(), car.getY());
        // 起始点缺失时退化为车辆当前位置，避免空指针
        Integer startX = agvTask.getStartPointX();
        Integer startY = agvTask.getStartPointY();
        Node startNode = new Node(startX != null ? startX : car.getX(), startY != null ? startY : car.getY());
        List<Node> oldPath = car.getPlannedPath();
        if (oldPath == null || oldPath.isEmpty()) {
            // 路径丢失（例如历史上 /agv/report 未回传 plannedPath），终止任务并复位车辆，
            // 否则这里会抛 NPE 中断每秒一次的整轮调度。
            agvTask.setStatus("failed");
            agvTask.setActualEndTime(LocalDateTime.now());
            agvTask.setDescription(agvCode + " 规划路径丢失，任务终止");
            car.setStatus("idle");
            car.setSpeed(0);
            car.setTaskId(null);
            car.setPlannedPath(null);
            car.setPathIndex(0);
            car.setIsCharge(0);
            agvPathMapper.deleteByAgvCode(agvCode);
            if (agvTask.getTaskId() != null) {
                agvTaskMapper.updateTask(agvTask);
            }
            return;
        }
        Node endNode = oldPath.get(oldPath.size()-1);
        LambdaQueryWrapper<AgvPath> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.ne(AgvPath::getAgvCode,car.getAgvCode());
        List<AgvPath> agvPaths = agvPathMapper.selectList(lambdaQueryWrapper);
        List<Node> pathList = new ArrayList<>();
        if (currentNode.equals(startNode) && agvTask.getPassMidPoint() == 1) {
            agvTask.setPassMidPoint(0);
            agvTaskMapper.updateTask(agvTask);
            car.setPathIndex(0);
            car.setPlannedPath(null);
            agvMapper.updateAgv(car);
        }
        if (agvTask.getPassMidPoint() == 1){
            pathList.addAll(agvUnlock.realTimePath(currentNode,startNode, agvPaths,agvCode));
            pathList.addAll(agvUnlock.realTimePath(startNode,endNode, agvPaths,agvCode));
        }else {
            pathList = agvUnlock.realTimePath(currentNode,endNode, agvPaths,agvCode);
        }
        car.setPlannedPath(pathList);
        // 检查是否有规划路径
        if (pathList.isEmpty()) {
            agvTask.setStatus("failed");
            agvTask.setActualEndTime(LocalDateTime.now());
            agvTask.setDescription(agvCode+"没有有效路径");
            car.setStatus("idle");
            car.setSpeed(0);
            agvPathMapper.deleteByAgvCode(car.getAgvCode());
            return;
        }

        int pathIndex = car.getPathIndex();
        if (pathIndex >= pathList.size()) {
            // 任务完成，回到空闲状态
            agvTask.setStatus("completed");
            agvTask.setActualEndTime(LocalDateTime.now());
            agvTask.setDescription(agvCode+"已经到达目标点位");
            car.setStatus("idle");
            car.setDescription(agvTask.getDescription());
            agvPathMapper.deleteByAgvCode(car.getAgvCode());
            return;
        }

        Node targetNode = pathList.get(pathIndex);
        //计算剩余路径
        List<Node> remainingPath = new ArrayList<>();
        for (int i = pathIndex; i < pathList.size(); i++) {
            remainingPath.add(pathList.get(i));
        }
        savePathToMap(remainingPath,agvCode);
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
                // 任务完成，回到空闲状态
                agvTask.setStatus("completed");
                agvTask.setActualEndTime(LocalDateTime.now());
                agvTask.setDescription(agvCode+"已经到达目标点位");
                car.setDescription(agvTask.getDescription());
                car.setStatus("idle");
                agvPathMapper.deleteByAgvCode(car.getAgvCode());
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
        car.setLastUpdate(Instant.now().toString());
    }

    /**
     * 保存路径点到地图
     * @param pathNodes 路径节点列表
     */
    public void savePathToMap(List<Node> pathNodes,String agvCode) {
        LambdaQueryWrapper<AgvPath> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(AgvPath::getAgvCode,agvCode);
        agvPathMapper.delete(lambdaQueryWrapper);
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        //List<AgvPath> paths = new ArrayList<>();
        // 添加路径点到地图
        for (Node node : pathNodes) {
            AgvPath path = new AgvPath();
            path.setX(node.getX());
            path.setY(node.getY());
            path.setType("path");
            path.setAgvCode(agvCode);
            path.setMapId(currentMap.getMapId());
            agvPathMapper.insert(path);
        }
    }

    /** 外部调用：删除一辆车（数据库记录 + 占用路径） */
    public synchronized void remove(String agvId) {
        if (agvId == null || agvId.isBlank()) {
            return;
        }
        agvMapper.delete(new QueryWrapper<AgvSnapshot>().eq("agv_code", agvId));
        agvPathMapper.deleteByAgvCode(agvId);
    }


}