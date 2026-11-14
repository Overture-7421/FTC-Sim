package dev.ftcsim.ui.glfw;

import static org.lwjgl.glfw.GLFW.glfwGetVersionString;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWaitEventsTimeout;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryUtil;

/**
 * The single thread that owns GLFW. GLFW wants initialisation, window management and event polling on one
 * thread, so everything that touches it (hidden context windows, joystick polling, hot-plug callbacks)
 * runs here, and other threads submit work through {@link #call(Callable)}.
 */
public final class GlfwThread implements AutoCloseable {
    private static final double WAIT_SECONDS = 0.008;

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<Runnable> pollers = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Thread thread = new Thread(this::run, "glfw");
    private volatile boolean running = true;
    private volatile boolean initialised;
    private volatile String version = "";
    private volatile String lastError = "";

    private GlfwThread() {
        thread.setDaemon(true);
    }

    /** Starts the thread and initialises GLFW without creating any window. */
    public static GlfwThread start() {
        GlfwThread glfw = new GlfwThread();
        glfw.thread.start();
        try {
            glfw.started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting GLFW", e);
        }
        if (!glfw.initialised) {
            throw new IllegalStateException("glfwInit failed: " + glfw.lastError);
        }
        return glfw;
    }

    public String version() {
        return version;
    }

    public String lastError() {
        return lastError;
    }

    /** Runs {@code action} on the GLFW thread and waits for its result. */
    public <T> T call(Callable<T> action) throws Exception {
        if (!running) {
            throw new IllegalStateException("GLFW has been stopped");
        }
        FutureTask<T> task = new FutureTask<>(action);
        tasks.add(task);
        glfwPostEmptyEvent();
        try {
            return task.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Registers work to run on every iteration of the GLFW loop, about every {@value #WAIT_SECONDS} s. */
    public void addPoller(Runnable poller) {
        pollers.add(poller);
    }

    public void removePoller(Runnable poller) {
        pollers.remove(poller);
    }

    @Override
    public void close() {
        running = false;
        glfwPostEmptyEvent();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        GLFWErrorCallback errors = GLFWErrorCallback.create((code, description) ->
                lastError = "0x" + Integer.toHexString(code) + " " + MemoryUtil.memUTF8(description));
        glfwSetErrorCallback(errors);
        initialised = glfwInit();
        if (initialised) {
            version = glfwGetVersionString();
        }
        started.countDown();
        if (!initialised) {
            errors.free();
            return;
        }
        try {
            while (running) {
                drainTasks();
                for (Runnable poller : pollers) {
                    poller.run();
                }
                glfwWaitEventsTimeout(WAIT_SECONDS);
            }
            drainTasks();
        } finally {
            glfwTerminate();
            glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private void drainTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }
}
