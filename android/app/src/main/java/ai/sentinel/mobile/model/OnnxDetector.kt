package ai.sentinel.mobile.model

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

data class Prediction(
    val category: Category?,
    val confidence: Double,
    val source: DetectionSource
)

/**
 * Runs the exported pipeline (imputer + scaler + forest) with ONNX Runtime Mobile.
 *
 * The session is created on demand and released under memory pressure: the model is 11 MB on
 * disk and the runtime arena is far larger, so holding it while idle would be wasteful on an
 * 8 GB tablet with a live packet loop.
 */
class OnnxDetector(
    private val context: Context,
    val contract: ModelContract
) : Closeable {

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadError: String? = null

    val isLoaded: Boolean get() = session != null

    val lastError: String? get() = loadError

    @Synchronized
    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        return try {
            val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
            val modelBytes = context.assets.open("model.onnx").use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(modelBytes, options).also {
                session = it
                loadError = null
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.java.simpleName
            null
        }
    }

    /**
     * Returns null when the model could not run. Callers must show "unclassified", never a
     * fabricated category.
     */
    fun predict(features: FloatArray): Prediction? {
        if (features.size != contract.featureCount) return null
        val active = ensureSession() ?: return null
        return try {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(features),
                longArrayOf(1L, features.size.toLong())
            ).use { input ->
                active.run(mapOf(contract.inputName to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val probabilities = result.get(1).value as Array<FloatArray>
                    val row = probabilities.firstOrNull() ?: return null
                    var bestIndex = 0
                    var best = -1f
                    row.forEachIndexed { index, value ->
                        if (value > best) {
                            best = value
                            bestIndex = index
                        }
                    }
                    val key = contract.classes.getOrNull(bestIndex) ?: return null
                    Prediction(
                        category = Category.fromKey(key),
                        confidence = best.toDouble(),
                        source = DetectionSource.AI
                    )
                }
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.java.simpleName
            null
        }
    }

    @Synchronized
    fun release() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
    }

    override fun close() {
        release()
        environment = null
    }
}
