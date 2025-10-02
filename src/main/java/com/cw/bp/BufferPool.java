package com.cw.bp;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存缓冲池 - 享元模式实现
 * <p>
 * 核心思想：
 * 1. 复用固定大小(slotSize)的ByteBuffer,避免频繁创建销毁
 * 2. 对于非标准大小的请求,直接分配新内存
 * 3. 使用条件变量实现等待/通知机制
 *
 * @author thisdcw
 * @date 2025年10月02日 0:15
 */
public class BufferPool {
    // ==================== 容量管理 ====================
    /**
     * 缓冲池总容量
     */
    private final int capacity;

    /**
     * 当前可用的空闲空间大小
     */
    private int free;

    /**
     * 标准插槽大小(享元对象的固定大小)
     */
    private final int slotSize;

    // ==================== 缓冲区管理 ====================
    /**
     * 可复用的标准插槽队列(存储slotSize大小的ByteBuffer)
     */
    private final Deque<ByteBuffer> slotQueue = new ArrayDeque<>();

    /**
     * 等待分配内存的线程条件队列
     */
    private final Deque<Condition> waiters = new ArrayDeque<>();

    // ==================== 并发控制 ====================
    /**
     * 保护共享资源的可重入锁
     */
    private Lock lock = new ReentrantLock();

    /**
     * 构造函数
     *
     * @param capacity 缓冲池总容量
     * @param slotSize 标准插槽大小
     */
    public BufferPool(int capacity, int slotSize) {
        this.capacity = capacity;
        this.slotSize = slotSize;
        // 初始时所有空间都是空闲的
        this.free = capacity;
    }

    /**
     * 归还ByteBuffer到缓冲池
     * <p>
     * 归还策略：
     * - 标准大小(slotSize): 清空后放入插槽队列,供后续复用
     * - 非标准大小: 直接释放空间,增加free计数
     *
     * @param buffer 要归还的缓冲区
     */
    public void deallocate(ByteBuffer buffer) {
        lock.lock();
        try {
            if (buffer.capacity() == this.slotSize) {
                // 标准插槽:清空数据后回收到队列(享元复用的核心)
                buffer.clear();
                this.slotQueue.addLast(buffer);
            } else {
                // 非标准大小:直接回收空间
                free += buffer.capacity();
            }

            // 有空间归还后,唤醒一个等待的线程
            if (!waiters.isEmpty()) {
                waiters.peek().signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从缓冲池分配ByteBuffer
     * <p>
     * 分配策略(优先级从高到低):
     * 1. 如果请求标准大小 && 有可复用插槽 → 直接返回复用的插槽
     * 2. 如果总可用空间足够 → 创建新ByteBuffer
     * 3. 空间不足 → 进入等待队列,直到有空间释放或超时
     *
     * @param size    请求的缓冲区大小
     * @param timeout 超时时间(微秒)
     * @return 分配的ByteBuffer
     * @throws InterruptedException 等待过程中被中断
     */
    public ByteBuffer allocate(int size, long timeout) throws InterruptedException {
        // 参数校验
        if (size <= 0 || size > capacity) {
            throw new RuntimeException("Invalid capacity: " + capacity);
        }

        lock.lock();
        try {
            // 【快速路径1】请求标准插槽 && 有可复用的 → 直接返回
            if (size == slotSize && !slotQueue.isEmpty()) {
                return slotQueue.pollFirst();
            }

            // 【快速路径2】总可用空间足够(空闲空间 + 插槽队列占用的空间)
            if ((free + slotQueue.size() * slotSize) >= size) {
                // 必要时回收插槽队列的空间
                freeUp(size);
                // 扣减空闲空间
                free -= size;
                return ByteBuffer.allocate(size);
            }

            // 【慢速路径】空间不足,需要等待其他线程释放
            Condition condition = lock.newCondition();
            // 加入等待队列
            waiters.addLast(condition);

            long remainTime = timeout;
            try {
                while (true) {
                    long start = System.currentTimeMillis();

                    // 等待被唤醒或超时
                    boolean wakeUp = condition.await(remainTime, TimeUnit.MICROSECONDS);
                    if (!wakeUp) {
                        throw new RuntimeException("Timeout waiting for condition");
                    }

                    // 被唤醒后再次尝试分配(逻辑同快速路径)
                    if (size == slotSize && !slotQueue.isEmpty()) {
                        return slotQueue.pollFirst();
                    }

                    if ((free + slotQueue.size() * slotSize) >= size) {
                        freeUp(size);
                        free -= size;
                        return ByteBuffer.allocate(size);
                    }

                    // 还是不够,继续等待(更新剩余时间)
                    remainTime -= System.currentTimeMillis() - start;
                }
            } finally {
                // 无论成功还是异常,都要从等待队列移除
                waiters.remove(condition);
            }

        } finally {
            // 释放锁前,如果还有等待者 && 还有可用空间,唤醒下一个
            if (!waiters.isEmpty() && !(free == 0 && slotQueue.isEmpty())) {
                waiters.peek().signal();
            }
            lock.unlock();
        }
    }

    /**
     * 释放足够的空间以满足分配需求
     * <p>
     * 策略:如果当前free不够,就从插槽队列中取出ByteBuffer并释放其空间
     * (将插槽队列中的对象转换为可分配的free空间)
     *
     * @param size 需要的空间大小
     */
    private void freeUp(int size) {
        while (size > free && !slotQueue.isEmpty()) {
            free += slotQueue.pollFirst().capacity();
        }
    }
}