package com.librevision.data.vision.rfdetr

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File

/** Must be created, run and closed on the same worker thread. */
class RfDetr(private val dir: File, val accelerator: Accelerator, private val threads: Int = 4) :
    Closeable {
    private val owned = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> keep(create: () -> T): T =
        try {
            create().also { owned.add(it) }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun graph(name: String): CompiledModel = keep {
        val options =
            CompiledModel.Options(accelerator).apply {
                if (accelerator == Accelerator.CPU)
                    cpuOptions = CompiledModel.CpuOptions(numThreads = threads, xnnPackFlags = 0x23)
                if (accelerator == Accelerator.GPU)
                    gpuOptions =
                        CompiledModel.GpuOptions(
                            precision = CompiledModel.GpuOptions.Precision.FP16,
                            serializationDir = File(dir, "gpu-cache").apply { mkdirs() }.path,
                            modelCacheKey = name + "-fp16-v1",
                            serializeProgramCache = true,
                        )
            }
        CompiledModel.create(File(dir, name).path, options)
    }

    private val a = graph("rfdetr_graphA_fp16.tflite")
    private val b = graph("rfdetr_graphB_fp16.tflite")

    private fun inputs(model: CompiledModel): List<TensorBuffer> =
        try {
            model.createInputBuffers().also { owned.addAll(it) }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun outputs(model: CompiledModel): List<TensorBuffer> =
        try {
            model.createOutputBuffers().also { owned.addAll(it) }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private val ai = inputs(a)
    private val ao = outputs(a)
    private val bi = inputs(b)
    private val bo = outputs(b)

    private fun slot(buffers: List<TensorBuffer>, size: Int): Int =
        try {
            buffers
                .indexOfFirst { it.readFloat().size == size }
                .also { check(it >= 0) { "Unexpected RF-DETR tensor shape ($size)" } }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private val classes = slot(ao, 576 * 91)
    private val coordinates = slot(ao, 576 * 4)
    private val memory = slot(ao, 576 * 256)
    private val memoryInput = slot(bi, 576 * 256)
    private val referenceInput = slot(bi, 300 * 4)
    private val boxes = slot(bo, 300 * 4)
    private val logits = slot(bo, 300 * 91)
    var lastModelMs = 0.0
        private set

    fun detect(input: FloatArray): List<Detection> {
        require(input.size == 3 * 384 * 384)
        val start = System.nanoTime()
        ai[0].writeFloat(input)
        a.run(ai, ao)
        val classScores = ao[classes].readFloat()
        val coords = ao[coordinates].readFloat()
        val features = ao[memory].readFloat()
        check(
            classScores.all { it.isFinite() } &&
                coords.all { it.isFinite() } &&
                features.all { it.isFinite() }
        ) {
            "Invalid encoder output"
        }
        val reference = DetectionMath.selectReferences(classScores, coords)
        bi[memoryInput].writeFloat(features)
        bi[referenceInput].writeFloat(reference)
        b.run(bi, bo)
        val decoded = DetectionMath.decode(bo[boxes].readFloat(), bo[logits].readFloat())
        lastModelMs = (System.nanoTime() - start) / 1e6
        return decoded
    }

    override fun close() {
        owned.asReversed().forEach { runCatching { it.close() } }
        owned.clear()
    }
}
