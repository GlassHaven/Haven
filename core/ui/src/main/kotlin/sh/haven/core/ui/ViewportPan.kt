package sh.haven.core.ui

/**
 * Constrain a viewport pan so the zoomed content rectangle always covers the
 * viewport — no black gap on any edge (#738).
 *
 * The content is drawn centred in the view (letterboxed by the fit-scale),
 * then a graphicsLayer applies [zoom] about the view centre followed by the
 * pan translation. Panning freely lets an off-centre pinch leave a residual
 * offset: at zoom 1 the content can sit shifted inside its letterbox, so one
 * edge shows black while the opposite bar doubles. This is the cover rule:
 * the scaled content's edges must stay at or beyond the viewport's edges.
 *
 * When the scaled content is smaller than the viewport on an axis (a
 * letterbox axis, or zoom below the cover-fill floor) no pan can cover it —
 * the bound collapses to 0 and the content stays centred, which keeps the
 * bars symmetric instead of pushing them all to one side.
 *
 * @param pan current translation on this axis, in view pixels.
 * @param contentSize the content's fit-scaled size on this axis (before [zoom]).
 * @param viewSize viewport size on this axis.
 * @param zoom the applied scale factor.
 */
fun clampPanToCover(pan: Float, contentSize: Float, viewSize: Float, zoom: Float): Float {
    if (viewSize <= 0f || contentSize <= 0f || zoom <= 0f) return 0f
    val maxPan = ((contentSize * zoom - viewSize) / 2f).coerceAtLeast(0f)
    return pan.coerceIn(-maxPan, maxPan)
}
