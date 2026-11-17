package com.lizhi.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.domain.po.AgvTask;
import com.lizhi.domain.query.AgvTaskQuery;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.mapper.AgvTaskMapper;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.result.PageResult;
import com.lizhi.service.AgvTaskService;
import com.lizhi.utils.AgvLockManager;
import com.lizhi.utils.PageBounds;
import org.springframework.stereotype.Service;


@Service
public class AgvTaskServiceImpl implements AgvTaskService {

    private final AgvTaskMapper agvTaskMapper;
    private final AgvMapper agvMapper;
    private final AgvPathMapper agvPathMapper;
    private final LiveStateCache liveStateCache;
    private final AgvLockManager agvLockManager;

    public AgvTaskServiceImpl(AgvTaskMapper agvTaskMapper, AgvMapper agvMapper,
                              AgvPathMapper agvPathMapper, LiveStateCache liveStateCache,
                              AgvLockManager agvLockManager) {
        this.agvLockManager = agvLockManager;
        this.agvTaskMapper = agvTaskMapper;
        this.agvMapper = agvMapper;
        this.agvPathMapper = agvPathMapper;
        this.liveStateCache = liveStateCache;
    }

    /**
     * 任务列表分页查询
     * @param agvTaskQuery
     * @return
     */
    @Override
    public PageResult pageQuery(AgvTaskQuery agvTaskQuery) {
        PageBounds.requireKeywordLength(agvTaskQuery.getTaskCode(), "taskCode");
        PageHelper.startPage(PageBounds.page(agvTaskQuery.getPage()), PageBounds.pageSize(agvTaskQuery.getPageSize()));
        Page<AgvTask> page = agvTaskMapper.pageQuery(agvTaskQuery);
        return new PageResult(page.getTotal(),page.getResult());
    }

    /**
     * 取消agv任务：立即把车辆复位为 idle 并清空 taskId/plannedPath/占用路径，
     * 然后刷新实时快照，保证写后立刻读 /agv/live 看到的是完整的新状态（R-V2-08）。
     * @param taskId
     */
    @Override
    public void cancelTask(Integer taskId) {
        AgvTask found = agvTaskMapper.getById(taskId);
        if (found == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        // 与 tick 共用车锁，任务与车辆在同一事务内更新
        agvLockManager.runInLockedTx(found.getAgvCode(), () -> {
            AgvTask agvTask = agvTaskMapper.getById(taskId);
            if (agvTask == null) {
                throw new IllegalArgumentException("任务不存在: " + taskId);
            }
            AgvSnapshot agv = agvMapper.getAgv(agvTask.getAgvCode());
            agvTask.setStatus("cancelled");
            if (agv != null) {
                agv.setStatus("idle");
                agv.setTaskId(null);
                agv.setPlannedPath(null);
                agv.setPathIndex(0);
                agv.setSpeed(0);
                agv.setIsCharge(0);
                agvMapper.updateAgv(agv);
                agvPathMapper.deleteByAgvCode(agv.getAgvCode());
            }
            agvTaskMapper.updateTask(agvTask);
        });
        liveStateCache.rebuild();
    }
}
