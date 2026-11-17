package com.lizhi.agv;

import com.lizhi.astart.map.GridMap;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.domain.po.AgvTask;
import com.lizhi.domain.po.Node;
import com.lizhi.mapper.AgvLogMapper;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.mapper.AgvTaskMapper;
import com.lizhi.utils.AgvLockManager;
import com.lizhi.utils.AgvSimulator;
import com.lizhi.utils.AgvUnlock;
import com.lizhi.utils.MapCache;
import com.lizhi.utils.TickContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仿真状态机单测：mapper 用 Mockito 模拟成一个"内存数据库"（一台车 + 一个任务），
 * 路径规划走真实的 A*（10x10 空地图，(9,0) 是充电站）。
 */
class AgvSimulatorTest {

    private static final String CODE = "AGV001";

    private AgvMapper agvMapper;
    private AgvTaskMapper agvTaskMapper;
    private AgvPathMapper agvPathMapper;
    private AgvLogMapper agvLogMapper;
    private MapCache mapCache;
    private MapCache.Snapshot mapSnapshot;

    /** "数据库"里的车和任务 */
    private AgvSnapshot storedCar;
    private AgvTask storedTask;
    private int carUpdates;
    private int taskUpdates;

    @BeforeEach
    void setUp() {
        agvMapper = mock(AgvMapper.class);
        agvTaskMapper = mock(AgvTaskMapper.class);
        agvPathMapper = mock(AgvPathMapper.class);
        agvLogMapper = mock(AgvLogMapper.class);
        mapCache = mock(MapCache.class);

        AgvMap map = new AgvMap();
        map.setMapId(1);
        map.setWidth(10);
        map.setHeight(10);
        GridMap grid = new GridMap(false, 10, 10);
        grid.setWeight(9, 0, -1); // 充电站格
        mapSnapshot = new MapCache.Snapshot(map, List.of(), List.of(new Node(9, 0)), grid);
        when(mapCache.current()).thenReturn(mapSnapshot);

        // getAgv / getById 每次返回副本，模拟"从数据库读出新对象"
        when(agvMapper.getAgv(CODE)).thenAnswer(inv -> copy(storedCar, new AgvSnapshot()));
        doAnswer(inv -> {
            storedCar = copy(inv.getArgument(0), new AgvSnapshot());
            carUpdates++;
            return null;
        }).when(agvMapper).updateAgv(any());
        when(agvTaskMapper.getById(any())).thenAnswer(inv -> storedTask == null ? null : copy(storedTask, new AgvTask()));
        doAnswer(inv -> {
            storedTask = copy(inv.getArgument(0), new AgvTask());
            taskUpdates++;
            return null;
        }).when(agvTaskMapper).updateTask(any());
        when(agvPathMapper.selectList(any())).thenReturn(new ArrayList<>());
    }

    private static <T> T copy(T source, T target) {
        if (source == null) {
            return null;
        }
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private AgvSimulator simulator(int replanEveryCells) {
        AgvLockManager locks = new AgvLockManager(new TransactionTemplate(mock(PlatformTransactionManager.class)));
        AgvUnlock planner = new AgvUnlock(mapCache, agvLogMapper);
        return new AgvSimulator(planner, agvPathMapper, agvMapper, agvTaskMapper, locks, mapCache, replanEveryCells, true);
    }

    private void tick(AgvSimulator simulator) {
        simulator.step(CODE, new TickContext(mapSnapshot, new ArrayList<>()));
    }

    private static AgvSnapshot car(String status, int x, int y) {
        return AgvSnapshot.builder().AgvCode(CODE).x(x).y(y).direction(0).status(status).battery(80).speed(0)
                .pathIndex(0).isCharge(0).description("暂无").lastUpdate("2026-01-01T00:00:00Z").build();
    }

    private void givenRunningTask(List<Node> plannedPath, int passMidPoint, int startX, int startY) {
        storedTask = new AgvTask();
        storedTask.setTaskId(7);
        storedTask.setAgvCode(CODE);
        storedTask.setStatus("processing");
        storedTask.setDescription("运输中");
        storedTask.setPassMidPoint(passMidPoint);
        storedTask.setStartPointX(startX);
        storedTask.setStartPointY(startY);
        storedCar = car("running", plannedPath.isEmpty() ? 0 : plannedPath.get(0).getX(),
                plannedPath.isEmpty() ? 0 : plannedPath.get(0).getY());
        storedCar.setSpeed(150);
        storedCar.setTaskId(7);
        storedCar.setPlannedPath(plannedPath.isEmpty() ? null : plannedPath);
    }

    @Test
    void idleCarIsNotWrittenWhenNothingChanges() {
        storedCar = car("idle", 3, 3);
        AgvSimulator simulator = simulator(1);
        for (int i = 0; i < 5; i++) {
            tick(simulator);
        }
        assertEquals(0, carUpdates, "空闲且无变化的车不应产生 UPDATE");
        assertEquals(0, taskUpdates);
        assertEquals("2026-01-01T00:00:00Z", storedCar.getLastUpdate(), "无变化时 lastUpdate 不刷新");
    }

    @Test
    void idleCarWithLeftoverTaskFieldsIsNormalizedOnce() {
        storedCar = car("idle", 3, 3);
        storedCar.setTaskId(7);
        storedCar.setSpeed(150);
        AgvSimulator simulator = simulator(1);
        tick(simulator);
        tick(simulator);
        assertEquals(1, carUpdates, "只在第一次归一化时写一次");
        assertNull(storedCar.getTaskId());
        assertEquals(0, storedCar.getSpeed());
    }

    @Test
    void runningCarMovesOneCellPerTickAndCompletes() {
        givenRunningTask(List.of(new Node(0, 0), new Node(3, 0)), 0, 0, 0);
        AgvSimulator simulator = simulator(1);

        tick(simulator);
        assertEquals(1, storedCar.getX());
        assertEquals("running", storedCar.getStatus());
        assertEquals(0, taskUpdates, "任务字段没变化时不写任务表");
        // 占用路径：1 次 delete + 1 次批量 insert，不再逐点 insert
        verify(agvPathMapper, times(1)).deleteByAgvCode(CODE);
        ArgumentCaptor<List<AgvPath>> batch = ArgumentCaptor.forClass(List.class);
        verify(agvPathMapper, times(1)).insertBatch(batch.capture());
        assertEquals(4, batch.getValue().size());
        verify(agvPathMapper, never()).insert(any(AgvPath.class));

        tick(simulator);
        tick(simulator);
        assertEquals(3, storedCar.getX());
        assertEquals("running", storedCar.getStatus());

        tick(simulator); // 到达后的下一个 tick 判定完成
        assertEquals("idle", storedCar.getStatus());
        assertEquals("completed", storedTask.getStatus());
        assertTrue(storedTask.getActualEndTime() != null);
        assertEquals(1, taskUpdates);

        tick(simulator); // idle 归一化
        assertNull(storedCar.getTaskId());
        assertNull(storedCar.getPlannedPath());
    }

    @Test
    void visitsPickupPointBeforeDestination() {
        // 车在 (0,0)，取货点 (2,0)，终点 (2,2)
        givenRunningTask(List.of(new Node(0, 0), new Node(2, 2)), 1, 2, 0);
        AgvSimulator simulator = simulator(1);
        boolean visitedPickup = false;
        for (int i = 0; i < 12 && !"idle".equals(storedCar.getStatus()); i++) {
            tick(simulator);
            visitedPickup |= storedCar.getX() == 2 && storedCar.getY() == 0;
        }
        assertTrue(visitedPickup, "必须先经过取货点");
        assertEquals("completed", storedTask.getStatus());
        assertEquals(0, storedTask.getPassMidPoint());
        assertEquals(2, storedCar.getX());
        assertEquals(2, storedCar.getY());
    }

    @Test
    void lostPathFailsTaskAndResetsCar() {
        givenRunningTask(List.of(), 0, 0, 0);
        tick(simulator(1));
        assertEquals("failed", storedTask.getStatus());
        assertEquals("idle", storedCar.getStatus());
        assertNull(storedCar.getTaskId());
    }

    @Test
    void unreachableTargetFailsOnlyThisTaskInsteadOfThrowing() {
        // 把终点四周封死：A* 无解
        GridMap grid = mapSnapshot.gridTemplate();
        grid.setWeight(4, 5, -1);
        grid.setWeight(6, 5, -1);
        grid.setWeight(5, 4, -1);
        grid.setWeight(5, 6, -1);
        givenRunningTask(List.of(new Node(0, 0), new Node(5, 5)), 0, 0, 0);
        tick(simulator(1)); // 不抛异常
        assertEquals("failed", storedTask.getStatus());
        assertEquals("idle", storedCar.getStatus());
        verify(agvLogMapper, times(1)).insert(any(com.lizhi.domain.po.AgvLog.class));
    }

    @Test
    void replanEveryCellsControlsHowOftenPathIsRewritten() {
        // N=1：每个 tick 都重规划并重写占用路径
        givenRunningTask(List.of(new Node(0, 0), new Node(8, 5)), 0, 0, 0);
        AgvSimulator everyTick = simulator(1);
        for (int i = 0; i < 6; i++) {
            tick(everyTick);
        }
        verify(agvPathMapper, times(6)).insertBatch(anyList());

        // N=3：6 个 tick 只重规划 2 次（第 1、4 个 tick），车照样每 tick 走一格
        setUp();
        givenRunningTask(List.of(new Node(0, 0), new Node(8, 5)), 0, 0, 0);
        AgvSimulator everyThree = simulator(3);
        for (int i = 0; i < 6; i++) {
            tick(everyThree);
        }
        verify(agvPathMapper, times(2)).insertBatch(anyList());
        assertEquals(6, storedCar.getX() + storedCar.getY(), "6 个 tick 走 6 格");

        // 跑到终点
        for (int i = 0; i < 20 && !"idle".equals(storedCar.getStatus()); i++) {
            tick(everyThree);
        }
        assertEquals("completed", storedTask.getStatus());
        assertEquals(8, storedCar.getX());
        assertEquals(5, storedCar.getY());
    }

    @Test
    void replanImmediatelyWhenCarIsNotWhereThePathExpects() {
        givenRunningTask(List.of(new Node(0, 0), new Node(8, 0)), 0, 0, 0);
        AgvSimulator simulator = simulator(3);
        tick(simulator);
        tick(simulator);
        verify(agvPathMapper, times(1)).insertBatch(anyList());
        // 外部（/agv/report）把车挪到别处
        storedCar.setX(4);
        storedCar.setY(4);
        tick(simulator);
        verify(agvPathMapper, times(2)).insertBatch(anyList());
        assertEquals(7, Math.abs(8 - storedCar.getX()) + Math.abs(0 - storedCar.getY()), "从新位置出发向终点靠近一格");
    }

    @Test
    void chargingCarChargesUntilFullThenCompletesTask() {
        storedTask = new AgvTask();
        storedTask.setTaskId(7);
        storedTask.setAgvCode(CODE);
        storedTask.setStatus("processing");
        storedTask.setDestination("C1");
        storedCar = car("running", 9, 0); // 已停在充电站上
        storedCar.setIsCharge(1);
        storedCar.setTaskId(7);
        storedCar.setBattery(80);
        storedCar.setPlannedPath(List.of(new Node(9, 0)));
        AgvSimulator simulator = simulator(1);

        tick(simulator);
        assertEquals("charging", storedCar.getStatus());
        verify(agvPathMapper, times(1)).deleteByAgvCode(CODE);
        for (int i = 0; i < 10 && !"idle".equals(storedCar.getStatus()); i++) {
            tick(simulator);
        }
        assertEquals("idle", storedCar.getStatus());
        assertTrue(storedCar.getBattery() > 95);
        assertEquals("completed", storedTask.getStatus());
        verify(agvPathMapper, times(1)).deleteByAgvCode(CODE);
    }

    @Test
    void removedCarIsNotWrittenBack() {
        storedCar = null;
        tick(simulator(1));
        verify(agvMapper, never()).updateAgv(any());
        verify(agvMapper, never()).insertAgv(any());
    }
}
