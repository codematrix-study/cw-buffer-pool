package com.cw.bp;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author thisdcw
 * @date 2025年10月02日 14:49
 */
public class BufferPoolTest {

    private BufferPool bufferPool;
    private static final int CAPACITY = 1024;
    private static final int SLOT_SIZE = 128;
    private static final long TIMEOUT = 5000; // 5秒超时

    @BeforeEach
    void setUp() {
        bufferPool = new BufferPool(CAPACITY, SLOT_SIZE);
    }

    // ==================== 基础功能测试 ====================

    @Test
    @DisplayName("测试1: 分配标准插槽大小的缓冲区")
    void testAllocateStandardSlot() throws InterruptedException {
        ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);

        assertNotNull(buffer);
        assertEquals(SLOT_SIZE, buffer.capacity());
    }

    @Test
    @DisplayName("测试2: 分配非标准大小的缓冲区")
    void testAllocateNonStandardSize() throws InterruptedException {
        ByteBuffer buffer = bufferPool.allocate(256, TIMEOUT);

        assertNotNull(buffer);
        assertEquals(256, buffer.capacity());
    }

    @Test
    @DisplayName("测试3: 分配超过容量的缓冲区应抛异常")
    void testAllocateExceedCapacity() {
        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(CAPACITY + 1, TIMEOUT);
        });
    }

    @Test
    @DisplayName("测试4: 分配非法大小(负数)应抛异常")
    void testAllocateInvalidSize() {
        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(-1, TIMEOUT);
        });

        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(0, TIMEOUT);
        });
    }

    // ==================== 享元模式复用测试 ====================

    @Test
    @DisplayName("测试5: 标准插槽的复用机制")
    void testSlotReuse() throws InterruptedException {
        // 第一次分配
        ByteBuffer buffer1 = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
        buffer1.putInt(12345); // 写入数据

        // 归还
        bufferPool.deallocate(buffer1);

        // 第二次分配应该拿到同一个对象(但已被clear)
        ByteBuffer buffer2 = bufferPool.allocate(SLOT_SIZE, TIMEOUT);

        assertSame(buffer1, buffer2, "应该复用同一个ByteBuffer对象");
        assertEquals(0, buffer2.position(), "归还后应该被clear,position应为0");
    }

    @Test
    @DisplayName("测试6: 非标准大小不会进入复用队列")
    void testNonStandardSizeNotReused() throws InterruptedException {
        ByteBuffer buffer1 = bufferPool.allocate(256, TIMEOUT);
        bufferPool.deallocate(buffer1);

        // 再次分配标准插槽,不应该拿到之前的非标准buffer
        ByteBuffer buffer2 = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
        assertNotSame(buffer1, buffer2);
    }

    // ==================== 容量管理测试 ====================

    @Test
    @DisplayName("测试7: 多次分配直到耗尽容量")
    void testExhaustCapacity() throws InterruptedException {
        List<ByteBuffer> buffers = new ArrayList<>();

        // 分配8个标准插槽 (8 * 128 = 1024)
        for (int i = 0; i < 8; i++) {
            ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
            buffers.add(buffer);
        }

        // 此时容量已耗尽,再分配应该超时
        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(SLOT_SIZE, 100); // 100微秒超时
        }, "容量耗尽时应抛出超时异常");
    }

    @Test
    @DisplayName("测试8: 归还后可以继续分配")
    void testAllocateAfterDeallocate() throws InterruptedException {
        // 耗尽容量
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffers.add(bufferPool.allocate(SLOT_SIZE, TIMEOUT));
        }

        // 归还一个
        bufferPool.deallocate(buffers.remove(0));

        // 应该可以再分配
        ByteBuffer newBuffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
        assertNotNull(newBuffer);
    }

    @Test
    @DisplayName("测试9: 混合分配标准和非标准大小")
    void testMixedAllocation() throws InterruptedException {
        ByteBuffer slot1 = bufferPool.allocate(SLOT_SIZE, TIMEOUT);     // 128
        ByteBuffer custom1 = bufferPool.allocate(200, TIMEOUT);          // 200
        ByteBuffer slot2 = bufferPool.allocate(SLOT_SIZE, TIMEOUT);     // 128
        ByteBuffer custom2 = bufferPool.allocate(300, TIMEOUT);          // 300

        // 已分配: 128 + 200 + 128 + 300 = 756
        // 剩余: 1024 - 756 = 268

        // 应该还能分配268字节
        ByteBuffer remaining = bufferPool.allocate(268, TIMEOUT);
        assertNotNull(remaining);

        // 再分配应该失败
        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(1, 100);
        });
    }

    // ==================== 边界条件测试 ====================

    @Test
    @DisplayName("测试13: freeUp机制 - 将插槽队列转换为free空间")
    void testFreeUpMechanism() throws InterruptedException {
        // 先分配并归还多个标准插槽
        for (int i = 0; i < 5; i++) {
            ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
            bufferPool.deallocate(buffer);
        }
        // 此时slotQueue中有5个插槽(5 * 128 = 640字节)

        // 分配一个大的非标准buffer(512字节)
        // 这会触发freeUp,将部分插槽转换为free空间
        ByteBuffer largeBuffer = bufferPool.allocate(512, TIMEOUT);
        assertNotNull(largeBuffer);
        assertEquals(512, largeBuffer.capacity());
    }

    @Test
    @DisplayName("测试14: 超时机制")
    void testTimeout() throws InterruptedException {
        // 耗尽容量
        for (int i = 0; i < 8; i++) {
            bufferPool.allocate(SLOT_SIZE, TIMEOUT);
        }

        // 尝试分配,应该在指定时间内超时
        long startTime = System.currentTimeMillis();

        assertThrows(RuntimeException.class, () -> {
            bufferPool.allocate(SLOT_SIZE, 1000); // 1000微秒 = 1毫秒
        });

        long elapsedTime = System.currentTimeMillis() - startTime;
        assertTrue(elapsedTime < 1000,
                "应该在合理时间内超时(实际: " + elapsedTime + "ms)");
    }

    @Test
    @DisplayName("测试15: 压力测试 - 快速分配释放")
    void testStressTest() throws InterruptedException {
        int iterations = 1000;

        for (int i = 0; i < iterations; i++) {
            ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
            buffer.putInt(i);
            bufferPool.deallocate(buffer);
        }

        // 测试通过即说明没有死锁或资源泄露
        assertTrue(true, "压力测试完成");
    }

    @Test
    @DisplayName("测试16: 清空机制验证")
    void testBufferClearOnDeallocate() throws InterruptedException {
        ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);

        // 写入数据
        buffer.putInt(0, 999);
        buffer.position(50);
        buffer.limit(100);

        // 归还
        bufferPool.deallocate(buffer);

        // 再次分配同一个buffer
        ByteBuffer reusedBuffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);

        assertSame(buffer, reusedBuffer);
        assertEquals(0, reusedBuffer.position(), "position应被reset");
        assertEquals(SLOT_SIZE, reusedBuffer.limit(), "limit应被reset");
        // 注意: clear()不会清除数据内容,只重置position/limit
    }
    // ==================== 并发测试(暂时不行) ====================

    @Test
    @DisplayName("测试10: 多线程并发分配和释放")
    void testConcurrentAllocateAndDeallocate() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Future<?> future = executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
                        // 模拟使用
                        buffer.putInt(j);
                        Thread.sleep(1);
                        // 归还
                        bufferPool.deallocate(buffer);
                    }
                } catch (Exception e) {
                    fail("并发操作不应失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        // 等待所有线程完成
        assertTrue(latch.await(30, TimeUnit.SECONDS), "所有线程应在30秒内完成");

        // 检查是否有异常
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                fail("线程执行出现异常: " + e.getCause().getMessage());
            }
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("测试11: 等待-通知机制")
    void testWaitNotifyMechanism() throws InterruptedException {
        // 主线程耗尽容量
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffers.add(bufferPool.allocate(SLOT_SIZE, TIMEOUT));
        }

        // 启动一个线程尝试分配(会进入等待)
        CountDownLatch allocateLatch = new CountDownLatch(1);
        Thread waiterThread = new Thread(() -> {
            try {
                ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
                assertNotNull(buffer);
                allocateLatch.countDown();
            } catch (InterruptedException e) {
                fail("不应被中断");
            }
        });

        waiterThread.start();
        Thread.sleep(100); // 确保waiterThread进入等待状态

        // 主线程归还一个buffer,应该唤醒等待线程
        bufferPool.deallocate(buffers.remove(0));

        assertTrue(allocateLatch.await(2, TimeUnit.SECONDS),
                "等待线程应该在2秒内被唤醒并成功分配");

        waiterThread.join();
    }

    @Test
    @DisplayName("测试12: 多个线程同时等待")
    void testMultipleWaiters() throws InterruptedException {
        // 耗尽容量
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffers.add(bufferPool.allocate(SLOT_SIZE, TIMEOUT));
        }

        // 启动3个等待线程
        int waiterCount = 3;
        CountDownLatch allAllocated = new CountDownLatch(waiterCount);

        for (int i = 0; i < waiterCount; i++) {
            new Thread(() -> {
                try {
                    ByteBuffer buffer = bufferPool.allocate(SLOT_SIZE, TIMEOUT);
                    assertNotNull(buffer);
                    allAllocated.countDown();
                } catch (InterruptedException e) {
                    fail("不应被中断");
                }
            }).start();
        }

        Thread.sleep(100); // 确保所有线程进入等待

        // 逐个归还buffer,应该依次唤醒等待线程
        for (int i = 0; i < waiterCount; i++) {
            Thread.sleep(50);
            bufferPool.deallocate(buffers.remove(0));
        }

        assertTrue(allAllocated.await(3, TimeUnit.SECONDS),
                "所有等待线程应该在3秒内被唤醒");
    }
}
