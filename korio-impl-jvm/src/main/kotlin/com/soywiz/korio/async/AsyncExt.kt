package com.soywiz.korio.async

import com.soywiz.korio.coroutine.Continuation
import com.soywiz.korio.coroutine.CoroutineContext
import com.soywiz.korio.coroutine.getCoroutineContext
import com.soywiz.korio.coroutine.korioStartCoroutine
import com.soywiz.korio.lang.CancellationException
import com.soywiz.korio.util.OS
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.experimental.startCoroutine

var _workerLazyPool: ExecutorService? = null
val workerLazyPool: ExecutorService by lazy {
	//val pool = Executors.newCachedThreadPool()
	val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
	_workerLazyPool = pool
	//Runtime.getRuntime().addShutdownHook(object : Thread() {
	//	override fun run() = pool.shutdown()
	//})
	pool
}

fun Executor.executeUpdatingTasksInProgress(action: () -> Unit) {
	tasksInProgress.incrementAndGet()
	this.execute {
		try {
			action()
		} finally {
			tasksInProgress.decrementAndGet()
		}
	}
}

fun <T> Promise<T>.jvmSyncAwait(): T {
	var completed = false
	val lock = Any()
	var error: Throwable? = null
	var result: T? = null

	this.then(resolved = {
		synchronized(lock) {
			completed = true
			result = it
		}
	}, rejected = {
		synchronized(lock) {
			completed = true
			error = it
		}
	})

	while (true) {
		synchronized(lock) {
			if (completed) {
				if (error != null) throw error!!
				if (result != null) return result!!
				throw IllegalStateException()
			}
		}
		Thread.sleep(10)
	}
}

suspend fun <T> executeInNewThread(task: suspend () -> T): T = suspendCancellableCoroutine<T> { c ->
	Thread {
		// @TODO: Check this
		task.startCoroutine(c)
	}.apply {
		isDaemon = true
	}.start()
}

operator fun ExecutorService.invoke(callback: () -> Unit) {
	this.execute(callback)
}

fun syncTest(block: suspend EventLoopTest.() -> Unit): Unit {
	sync(el = EventLoopTest(), step = 10, block = block)
}

fun <TEventLoop : EventLoop> sync(el: TEventLoop, step: Int = 10, block: suspend TEventLoop.() -> Unit): Unit {
	if (OS.isJs) throw UnsupportedOperationException("sync block is not supported on javascript target. It is intended for testing.")
	var result: Any? = null

	tasksInProgress.incrementAndGet()
	block.korioStartCoroutine(el, object : Continuation<Unit> {
		override val context: CoroutineContext = el.coroutineContext

		override fun resume(value: Unit) = run {
			tasksInProgress.decrementAndGet()
			result = value
		}

		override fun resumeWithException(exception: Throwable) = run {
			tasksInProgress.decrementAndGet()
			result = exception
		}
	})

	while (result == null) {
		Thread.sleep(1L)
		el.step(step)
	}
	if (result is Throwable) throw result as Throwable
	return Unit
}

// Wait for a suspension block for testing purposes
fun <T> sync(block: suspend () -> T): T {
	if (OS.isJs) throw UnsupportedOperationException("sync block is not supported on javascript target. It is intended for testing.")
	var result: Any? = null

	val el = eventLoopFactoryDefaultImpl.createEventLoop()
	tasksInProgress.incrementAndGet()
	block.korioStartCoroutine(object : Continuation<T> {
		override val context: CoroutineContext = CoroutineCancelContext() + EventLoopCoroutineContext(el)

		override fun resume(value: T) = run {
			tasksInProgress.decrementAndGet()
			result = value
		}

		override fun resumeWithException(exception: Throwable) = run {
			tasksInProgress.decrementAndGet()
			result = exception
		}
	})

	while (result == null) Thread.sleep(1L)
	if (result is Throwable) throw result as Throwable
	@Suppress("UNCHECKED_CAST")
	return result as T
}

suspend fun <T> executeInWorkerSync(task: CheckRunning.() -> T): T = suspendCancellableCoroutine<T> { c ->
	//println("executeInWorker")
	tasksInProgress.incrementAndGet()
	workerLazyPool.execute {
		val checkRunning = object : CheckRunning {
			override var cancelled = false
			override val coroutineContext: CoroutineContext get() = c.context

			init {
				c.onCancel {
					cancelled = true
				}
			}

			override fun checkCancelled() {
				if (cancelled) throw CancellationException()
			}
		}

		try {
			c.resume(task(checkRunning))
		} catch (t: Throwable) {
			c.resumeWithException(t)
		} finally {
			tasksInProgress.decrementAndGet()
		}
	}
}

suspend fun <T> executeInWorkerSafe(task: suspend () -> T): T {
	val ctx = getCoroutineContext()
	val deferred = Promise.Deferred<T>()
	workerLazyPool.executeUpdatingTasksInProgress {
		go(ctx) {
			try {

				deferred.resolve(task())
			} catch (t: Throwable) {
				deferred.reject(t)
			}
		}
	}
	return deferred.promise.await()
}

suspend fun <T> executeInWorker(task: suspend CheckRunning.() -> T): T = suspendCancellableCoroutine<T> { c ->
	//println("executeInWorker")
	tasksInProgress.incrementAndGet()
	workerLazyPool.execute {
		val checkRunning = object : CheckRunning {
			override var cancelled = false

			override val coroutineContext: CoroutineContext = c.context

			init {
				c.onCancel {
					cancelled = true
				}
			}

			override fun checkCancelled() {
				if (cancelled) throw CancellationException()
			}
		}

		try {
			task.korioStartCoroutine(checkRunning, c)
		} catch (t: Throwable) {
			c.resumeWithException(t)
		} finally {
			tasksInProgress.decrementAndGet()
		}
	}
}