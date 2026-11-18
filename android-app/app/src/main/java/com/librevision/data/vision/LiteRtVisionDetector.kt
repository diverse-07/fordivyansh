package com.librevision.data.vision

import com.google.ai.edge.litert.Accelerator
import com.librevision.data.vision.rfdetr.CocoLabels
import com.librevision.data.vision.rfdetr.RfDetr
import com.librevision.domain.vision.*
import java.io.File

class LiteRtVisionDetector(private val directory: File, private val status: (String) -> Unit) :
    VisionDetector {
    private var engine: RfDetr =
        try {
            warm(Accelerator.GPU)
        } catch (failure: Exception) {
            status("GPU unavailable. Preparing CPU fallback…")
            warm(Accelerator.CPU)
        } catch (failure: LinkageError) {
            status("GPU unavailable. Preparing CPU fallback…")
            warm(Accelerator.CPU)
        }
    val modelMs
        get() = engine.lastModelMs

    private fun warm(accelerator: Accelerator): RfDetr {
        val model = RfDetr(directory, accelerator)
        try {
            model.detect(FloatArray(3 * 384 * 384))
            return model
        } catch (error: Throwable) {
            model.close()
            throw error
        }
    }

    override fun detect(input: FloatArray): List<Detection> {
        val values =
            try {
                engine.detect(input)
            } catch (error: Exception) {
                if (engine.accelerator != Accelerator.GPU) throw error
                engine.close()
                status("GPU unavailable. Switching to CPU…")
                engine = warm(Accelerator.CPU)
                engine.detect(input)
            }
        return values.map {
            Detection(
                CocoLabels.names[it.classId],
                it.score,
                NormalizedBox(it.left, it.top, it.right, it.bottom),
            )
        }
    }

    override fun close() = engine.close()
}
