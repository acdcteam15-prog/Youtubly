package com.youtubly.app.blur

/**
 * The gender models packed inside the APK (app/src/main/assets/models/), so the user can try them
 * and keep whichever works best on their phone. Pure data + logic: no Android types, unit-tested.
 *
 * Each model carries its OWN preprocessing profile. Which output means "female" and how pixels are
 * scaled differ per model, so applying one global setting to every model would silently flip men and
 * women when the user switches. [BundledModel.femaleIndex] / [BundledModel.normalization] are what
 * get applied when a model is chosen.
 */
data class BundledModel(
    /** Stable id; also the asset file name without extension. Never change once shipped. */
    val id: String,
    val title: String,
    val blurb: String,
    val fileName: String,
    /** Which output slot is "female" for [1,2] models. For a single-output sigmoid model it says
     *  whether the value is P(female) (1) or P(male) (0, meaning female = 1 - value). */
    val femaleIndex: Int,
    val normalization: Normalization,
    val recommended: Boolean = false,
) {
    val assetPath get() = "${BundledModels.ASSET_DIR}/$fileName"
}

object BundledModels {
    const val ASSET_DIR = "models"

    /**
     * All five were checked offline: valid TFLite, input [1,128,128,3] float32.
     *  - The four "utk_*" models return [1,2]; the original app treats index 0 as male, index 1 as
     *    female, and scales pixels by 1/255.
     *  - "mobilenet_sigmoid" returns a single sigmoid value that turned out to be P(male); see its entry.
     */
    val all: List<BundledModel> = listOf(
        BundledModel(
            id = "utk_lite_q",
            title = "Lite · small",
            blurb = "Smallest and fastest (0.6 MB). Good first choice on older or slower phones.",
            fileName = "utk_lite_q.tflite",
            femaleIndex = 1, normalization = Normalization.ZERO_ONE,
            recommended = true,
        ),
        BundledModel(
            id = "utk_lite",
            title = "Lite · full precision",
            blurb = "Same network as Lite · small with full 32-bit weights (1.3 MB). Slightly steadier scores.",
            fileName = "utk_lite.tflite",
            femaleIndex = 1, normalization = Normalization.ZERO_ONE,
        ),
        BundledModel(
            id = "utk_q",
            title = "Standard · small",
            blurb = "The larger network in compact form (1.3 MB). Better accuracy than Lite, still light.",
            fileName = "utk_q.tflite",
            femaleIndex = 1, normalization = Normalization.ZERO_ONE,
        ),
        BundledModel(
            id = "utk_full",
            title = "Standard · full precision",
            blurb = "Most accurate of the four UTKFace models (2.6 MB). Slowest; best on a recent phone.",
            fileName = "utk_full.tflite",
            femaleIndex = 1, normalization = Normalization.ZERO_ONE,
        ),
        BundledModel(
            id = "mobilenet_sigmoid",
            title = "MobileNet · single score",
            blurb = "A different network (5 MB) that gives one male/female score. Less consistent than the " +
                "others in testing, so treat it as a backup to try on videos where the rest struggle.",
            fileName = "mobilenet_sigmoid.tflite",
            // MEASURED, not assumed: on 8 labelled faces its single value was high for men (0.94-0.99)
            // and low for women, so the sigmoid is P(male). Slot 0 = female for a single-output model.
            femaleIndex = 0, normalization = Normalization.ZERO_ONE,
        ),
    )

    /** The one used the first time, before the user picks anything. */
    val default: BundledModel get() = all.first { it.recommended }

    fun byId(id: String?): BundledModel? = all.firstOrNull { it.id == id }
}

/**
 * Which model is installed and where it came from, as one small marker string stored next to the
 * model. Extends the older single-model markers so existing installs keep working:
 *   ""            nothing recorded
 *   "bundled:<x>" a packed model; <x> is its id, or (older builds) a SHA-256
 *   "custom"      the user imported their own file: never overwritten
 *   "removed"     the user removed the model on purpose: not silently put back
 */
object ModelOrigin {
    private const val PREFIX = "bundled:"

    fun forModel(id: String) = PREFIX + id
    fun isBundled(origin: String) = origin.startsWith(PREFIX)

    /** The bundled model id named by [origin], or null when it isn't a bundled marker. */
    fun bundledId(origin: String): String? = origin.removePrefix(PREFIX).takeIf { origin.startsWith(PREFIX) && it.isNotEmpty() }

    /** What to do on app start. Pure, so every case is tested. */
    enum class Action { INSTALL_DEFAULT, MARK_CUSTOM, KEEP }

    fun onStart(origin: String, installed: Boolean): Action = when {
        origin == "custom" || origin == "removed" -> Action.KEEP           // the user's choice always wins
        origin.isEmpty() && installed -> Action.MARK_CUSTOM                  // imported before bundling: it's theirs
        origin.isEmpty() -> Action.INSTALL_DEFAULT                           // first launch
        isBundled(origin) && installed -> {
            // Installed and known → keep. An OLD build's marker is a SHA-256, not a model id; that
            // model is still on disk, so leave it rather than swapping it under the user.
            Action.KEEP
        }
        isBundled(origin) -> Action.INSTALL_DEFAULT                          // marker says bundled but the file vanished
        else -> Action.KEEP
    }
}
