package com.titipong.photocap360

/**
 * Picks the alignment hypothesis whose seams look best (after Zhang & Liu, "Parallax-tolerant
 * Image Stitching", CVPR 2014: judge an alignment by how well it can be *seamed*, not by how small
 * the average match error is — with parallax the two disagree).
 *
 * Every candidate is composited on a small grid (gains + alpha-expansion seams, no rendering), and
 * scored by
 *     score = E_seam + HOLE_COST · (uncovered cells)
 * where E_seam = Σ colour differences across the final seams (StitchCore.SeamField.seamEnergy).
 * The first candidate is the default; another one must beat it by [MARGIN] to be chosen, so noise
 * in the score doesn't flip the choice.
 */
object SeamSelect {

    class Choice(val index: Int, val scores: DoubleArray)

    private const val EVAL_W = 1024
    private const val EVAL_GRID = 512
    private const val HOLE_COST = 240.0
    private const val MARGIN = 0.97

    fun frames(images: List<FrameImage>, c: Aligner.Candidate): List<FrameImage> = images.mapIndexed { k, f ->
        FrameImage(f.pixels, f.width, f.height, c.rotations[k], f.tanH * c.fovScale, f.tanV * c.fovScale, c.warps.getOrNull(k))
    }

    fun score(images: List<FrameImage>, c: Aligner.Candidate, yaw0: Double): Double {
        val core = StitchCore(frames(images, c), yaw0)
        core.rhoWall = c.rhoWall; core.kappa = c.kappa
        core.multiBand = false
        core.sweeps = 2
        core.prepare(EVAL_W, EVAL_W / 2, EVAL_GRID)
        val st = core.seamStats ?: return Double.MAX_VALUE
        return st.seamEnergyAfter + HOLE_COST * st.uncovered
    }

    fun pick(images: List<FrameImage>, cands: List<Aligner.Candidate>, yaw0: Double, progress: (Float) -> Unit = {}): Choice {
        val scores = DoubleArray(cands.size)
        for ((k, c) in cands.withIndex()) {
            scores[k] = try { score(images, c, yaw0) } catch (e: Throwable) { Double.MAX_VALUE }
            progress((k + 1).toFloat() / cands.size)
        }
        var best = 0
        for (k in 1 until cands.size) if (scores[k] < scores[best]) best = k
        if (best != 0 && scores[best] >= MARGIN * scores[0]) best = 0
        return Choice(best, scores)
    }
}
