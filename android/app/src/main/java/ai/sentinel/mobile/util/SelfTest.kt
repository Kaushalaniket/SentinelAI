package ai.sentinel.mobile.util

import android.content.Context
import android.os.SystemClock
import ai.sentinel.mobile.model.ModelContract
import ai.sentinel.mobile.model.OnnxDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * On-device parity check.
 *
 * Runs the exported ONNX graph over the bundled 140 labelled flows and compares each prediction
 * with the label the scikit-learn pipeline produced on the desktop. This is what makes the
 * "the model on the tablet is the model that was trained" claim checkable on the device itself.
 *
 * The probability-delta figure is a desktop measurement (2.7e-4); it cannot be recomputed here
 * because only the reference labels are bundled, not reference probability vectors.
 */
class SelfTest(
    private val context: Context,
    private val contract: ModelContract,
    private val detector: OnnxDetector
) {

    data class Result(
        val rows: Int,
        val agree: Int,
        val modelLoaded: Boolean,
        val elapsedMs: Long,
        val error: String? = null
    ) {
        val passed: Boolean get() = rows > 0 && agree == rows && error == null
        val summary: String
            get() = when {
                error != null -> "Failed: $error"
                rows == 0 -> "No rows bundled"
                else -> "$agree/$rows agree (${elapsedMs}ms)"
            }
    }

    suspend fun run(): Result = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        try {
            val csv = context.assets.open("sample_flows.csv").bufferedReader().use { it.readLines() }
            if (csv.size < 2) return@withContext Result(0, 0, false, 0, "sample_flows.csv is empty")
            val header = parseLine(csv.first())
            val expectedText = context.assets.open("sample_expected.json")
                .bufferedReader().use { it.readText() }
            val expected = JSONObject(expectedText).getJSONArray("predicted_labels")

            val columnIndex = HashMap<String, Int>(header.size)
            header.forEachIndexed { index, name -> columnIndex[name] = index }

            var rows = 0
            var agree = 0
            for (lineIndex in 1 until csv.size) {
                val line = csv[lineIndex]
                if (line.isBlank()) continue
                val cells = parseLine(line)
                val features = FloatArray(contract.featureCount) { slot ->
                    val name = contract.featureNames[slot]
                    val index = columnIndex[name]
                    val value = if (index != null && index < cells.size) cells[index].toDoubleOrNull() else null
                    value?.toFloat() ?: Float.NaN
                }
                val prediction = detector.predict(features)
                if (lineIndex - 1 < expected.length() && prediction?.category != null) {
                    if (prediction.category.key == expected.getString(lineIndex - 1)) agree++
                }
                rows++
            }
            Result(
                rows = rows,
                agree = agree,
                modelLoaded = detector.isLoaded,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                error = detector.lastError.takeIf { agree == 0 && rows > 0 }
            )
        } catch (t: Throwable) {
            Result(0, 0, false, SystemClock.elapsedRealtime() - started, t.message ?: t::class.java.simpleName)
        }
    }

    /** Minimal CSV splitter that respects double quotes. */
    private fun parseLine(line: String): List<String> {
        val cells = ArrayList<String>(96)
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> {
                    if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else quoted = !quoted
                }
                char == ',' && !quoted -> {
                    cells.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        cells.add(current.toString())
        return cells
    }
}
