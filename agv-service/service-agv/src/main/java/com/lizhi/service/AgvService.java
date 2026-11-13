package com.lizhi.service;

import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.dto.AgvSnapshotDTO;
import com.lizhi.domain.dto.AgvTaskDTO;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.domain.po.Node;

import java.util.List;

public interface AgvService {
    /**
     * 开始执行任务
     * @param agvTaskDTO
     */
    List<Node> startTask(AgvTaskDTO agvTaskDTO) throws NoPathFoundException, InterruptedException;

    /**
     * 只预览任务路径（不落库、不改车辆状态）
     * @param agvTaskDTO agvCode / startPoint / endPoint / currentPoint(可缺省)
     * @return 规划出的路径节点
     */
    List<Node> previewTask(AgvTaskDTO agvTaskDTO) throws NoPathFoundException;

    /**
     * 获取所有agv小车状态
     * @return
     */
    List<AgvSnapshot> getAllAgv() throws NoPathFoundException;

    /**
     * 获取agv小车
     * @param agvCode
     * @return
     */
    AgvSnapshot getAgv(String agvCode);

    /**
     * 记录小车当前位置
     * @param agvSnapshotDTO
     */
    void reportAgv(AgvSnapshotDTO agvSnapshotDTO);

    /**
     * 动态加车：从现有 AGVplusNNNN 最大编号 +1 连续编号，直接写入 agv_snapshot
     * @param mun 本次新增数量 1..50
     * @return 新建车辆编号列表
     */
    List<String> batchAgv(Integer mun);

    /**
     * 遥控小车充电
     * @param agvCode
     */
    void chargeAgv(String agvCode) throws NoPathFoundException;

    /**
     * 停止小车运行
     * @param agvSnapshotDTO
     */
    void stopAgv(AgvSnapshotDTO agvSnapshotDTO);

    /**
     * 启动agv小车运动
     * @param agvSnapshotDTO
     */
    void startAgv(AgvSnapshotDTO agvSnapshotDTO);

    /**
     * 设置agv小车速度
     * @param agvSnapshotDTO
     */
    void setSpeed(AgvSnapshotDTO agvSnapshotDTO);

    /**
     * 清除所有任务
     */
    void clearAllTask();
}
