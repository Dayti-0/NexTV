package tv.nextv.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Pictogrammes absents du jeu d'icônes de base de Material (tracés Material Icons, licence Apache 2.0),
 * pour éviter d'embarquer la bibliothèque d'icônes étendue.
 */
object NexTvIcons {

    val Tv: ImageVector = icon(
        "Tv",
        "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z",
    )

    val Link: ImageVector = icon(
        "Link",
        "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1z" +
            "M8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z",
    )

    val Folder: ImageVector = icon(
        "Folder",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )

    val Grid: ImageVector = icon(
        "Grid",
        "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z",
    )

    val Guide: ImageVector = icon(
        "Guide",
        "M3,5h18v2H3zM3,11h11v2H3zM16,11h5v2h-5zM3,17h6v2H3zM11,17h10v2H11z",
    )

    val Movie: ImageVector = icon(
        "Movie",
        "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z",
    )

    val Series: ImageVector = icon(
        "Series",
        "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4" +
            "c0,-1.1 -0.9,-2 -2,-2zM12,14.5v-9l6,4.5 -6,4.5z",
    )

    val Speed: ImageVector = icon(
        "Speed",
        "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85" +
            "a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z",
    )

    val Cloud: ImageVector = icon(
        "Cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13" +
            "c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )

    /** Replay : programme passé enregistré par le serveur. */
    val Replay: ImageVector = icon(
        "Replay",
        "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
    )

    val Football: ImageVector = icon(
        "Football",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,5.3l1.35,-0.95c1.82,0.56 3.37,1.76 4.38,3.34l-0.39,1.34 -1.35,0.46L13,6.7L13,5.3z" +
            "M9.65,4.35L11,5.3v1.4L7.01,9.49l-1.35,-0.46 -0.39,-1.34c1.01,-1.57 2.56,-2.77 4.38,-3.34z" +
            "M7.08,17.11l-1.14,0.1C4.73,15.81 4,13.99 4,12c0,-0.12 0.01,-0.23 0.02,-0.35l1,-0.73 1.38,0.48 1.46,4.34 -0.78,1.37z" +
            "M14.5,19.59c-0.79,0.26 -1.63,0.41 -2.5,0.41s-1.71,-0.15 -2.5,-0.41l-0.69,-1.49 0.64,-1.1h5.11l0.64,1.11 -0.7,1.48z" +
            "M14.27,15L9.73,15l-1.35,-4.02L12,8.44l3.63,2.54L14.27,15z" +
            "M18.06,17.21l-1.14,-0.1 -0.79,-1.37 1.46,-4.34 1.39,-0.47 1,0.73c0.01,0.11 0.02,0.22 0.02,0.34 0,1.99 -0.73,3.81 -1.94,5.21z",
    )

    val Pause: ImageVector = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")

    val FastForward: ImageVector = icon("FastForward", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")

    val FastRewind: ImageVector = icon("FastRewind", "M11,18L11,6l-8.5,6 8.5,6zM11.5,12l8.5,6L20,6l-8.5,6z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
