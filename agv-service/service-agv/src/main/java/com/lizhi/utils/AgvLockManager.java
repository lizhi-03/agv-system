package com.lizhi.utils;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 车辆状态锁：每台车一把（可重入）。
 * <p>
 * 所有会写 agv_snapshot 某一行的代码（每秒 tick 的 step、建任务、充电、启停、调速、上报、取消、清空）
 * 都必须先拿到该车的锁，再做"读-算-写"。这样：
 * <ul>
 *   <li>写请求只与同一台车的 tick 互斥，不再被整轮 tick（所有车）阻塞；</li>
 *   <li>tick 不会用旧快照覆盖刚下发的任务（历史上"新车停在 0,0 不执行任务"的根因）。</li>
 * </ul>
 * 加锁顺序统一为：先车锁，后数据库事务（{@link #inLockedTx}），避免"事务已持有行锁再等车锁"的死锁。
 * 一个线程同一时间只持有一台车的锁。
 */
@Component
public class AgvLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final TransactionTemplate transactionTemplate;

    public AgvLockManager(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /** 持有车锁执行 */
    public <T> T withLock(String agvCode, Supplier<T> action) {
        if (agvCode == null) {
            return action.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(agvCode, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 持有车锁、并在同一个数据库事务内执行：多表写要么全部成功，要么整体回滚 */
    public <T> T inLockedTx(String agvCode, Supplier<T> action) {
        return withLock(agvCode, () -> transactionTemplate.execute(status -> action.get()));
    }

    /** {@link #inLockedTx(String, Supplier)} 的无返回值版本 */
    public void runInLockedTx(String agvCode, Runnable action) {
        inLockedTx(agvCode, () -> {
            action.run();
            return null;
        });
    }
}
