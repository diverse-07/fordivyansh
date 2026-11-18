package com.librevision.data.vision.depth

import android.content.Context
import android.graphics.*
import android.os.*
import com.librevision.data.models.AppModelManager
import com.librevision.domain.vision.DepthFrame
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.*
import kotlinx.coroutines.flow.*

data class DepthState(
    val frame: DepthFrame? = null,
    val message: String = "Estimated distance unavailable.",
)

/**
 * Latest bitmap only, independent CPU thread; never blocks detector inference waiting for depth.
 */
class DepthProcessor(private val context: Context, private val models: AppModelManager) :
    AutoCloseable {
    private data class Job(val image: Bitmap, val time: Long, val generation: Long)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicReference<Job?>()
    private val scheduled = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var engine: MetricDepthEngine? = null // CPU executor only.
    @Volatile private var closed = false
    private val mutable = MutableStateFlow(DepthState())
    val state = mutable.asStateFlow()

    /** Ownership transfers here after detector preprocessing. No second ImageProxy conversion. */
    fun submit(image: Bitmap, time: Long) {
        if (closed) {
            image.recycle()
            return
        }
        val epoch = generation.get()
        pending.getAndSet(Job(image, time, epoch))?.image?.recycle()
        if (closed) {
            pending.getAndSet(null)?.image?.recycle()
            return
        }
        schedule()
    }

    private fun schedule() {
        if (closed || !scheduled.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    while (true) {
                        val job = pending.getAndSet(null) ?: break
                        try {
                            if (job.generation != generation.get() || closed) continue
                            val active =
                                engine
                                    ?: MetricDepthEngine(context, models.readyFiles().depth).also {
                                        engine = it
                                    }
                            val frame = active.infer(job.image, job.time)
                            main.post {
                                if (!closed && job.generation == generation.get())
                                    mutable.value =
                                        DepthState(
                                            frame,
                                            "Monocular distance estimate · indoor model, uncalibrated.",
                                        )
                            }
                        } catch (_: Exception) {
                            main.post {
                                if (!closed && job.generation == generation.get())
                                    mutable.value =
                                        DepthState(
                                            message =
                                                "Depth unavailable. Object alerts continue without distance."
                                        )
                            }
                        } catch (_: LinkageError) {
                            main.post {
                                if (!closed && job.generation == generation.get())
                                    mutable.value =
                                        DepthState(
                                            message =
                                                "Depth runtime unavailable. Object alerts continue."
                                        )
                            }
                        } finally {
                            job.image.recycle()
                        }
                    }
                } finally {
                    scheduled.set(false)
                    if (pending.get() != null && !closed) schedule()
                }
            }
        } catch (_: RejectedExecutionException) {
            scheduled.set(false)
            pending.getAndSet(null)?.image?.recycle()
        }
    }

    fun stop() {
        generation.incrementAndGet()
        pending.getAndSet(null)?.image?.recycle()
        mutable.value = DepthState()
        if (!closed)
            worker.execute {
                engine?.close()
                engine = null
            }
    }

    override fun close() {
        stop()
        closed = true
        worker.shutdown()
    }
}
