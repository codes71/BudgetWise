package app.budgetwise.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A path drawn filled (the dot in [Lucide.Tag]) instead of stroked. */
private class Dot(val data: String)

private fun dot(data: String) = Dot(data)

/**
 * Builds a Lucide-style icon: 24 x 24, round caps and joins, stroke [strokeWidth] (the kit's 1.75 unless a board says
 * otherwise). Black strokes, so Compose's `Icon` tint gives it its color. [paths] are SVG path strings, or [Dot]s.
 */
private fun lucide(name: String, vararg paths: Any, strokeWidth: Float = 1.75f): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            for (p in paths) {
                when (p) {
                    is Dot -> addPath(pathData = addPathNodes(p.data), fill = SolidColor(Color.Black))
                    else -> addPath(
                        pathData = addPathNodes(p as String),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = strokeWidth,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }
        .build()

/**
 * The kit's icons, copied path for path from its boards (Lucide, ISC license). Circles and rectangles are written as
 * paths. Decorative everywhere: the text next to each one says what it means.
 */
object Lucide {
    /** Gear (settings). */
    val Settings by lazy { lucide("Settings", "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z") }

    /** Synced. */
    val CloudCheck by lazy { lucide("CloudCheck", "m17 15-5.5 5.5L9 18", "M5 17.743A7 7 0 1 1 15.71 10h1.79a4.5 4.5 0 0 1 1.5 8.742") }

    /** Offline. */
    val CloudOff by lazy { lucide("CloudOff", "m2 2 20 20", "M5.782 5.782A7 7 0 0 0 9 19h8.5a4.5 4.5 0 0 0 1.307-.193", "M21.532 16.5A4.5 4.5 0 0 0 17.5 10h-1.79A7.008 7.008 0 0 0 10 5.07") }

    /** Not synced: the server could not be reached. */
    val CloudAlert by lazy { lucide("CloudAlert", "M12 12v4", "M12 20h.01", "M17 18h.5a1 1 0 0 0 0-9h-1.79A7 7 0 1 0 7 17.708") }

    /** Food and Drink. */
    val Utensils by lazy { lucide("Utensils", "M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2", "M7 2v20", "M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7") }

    /** Transport. */
    val Bus by lazy { lucide("Bus", "M8 6v6", "M15 6v6", "M2 12h19.6", "M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3", "M5 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z", "M9 18h5", "M14 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z") }

    /** Income. */
    val ArrowDownLeft by lazy { lucide("ArrowDownLeft", "M17 7 7 17", "M17 17H7V7") }

    /** Expense. */
    val ArrowUpRight by lazy { lucide("ArrowUpRight", "M7 7h10v10", "M7 17 17 7") }

    /** Groceries. */
    val Cart by lazy { lucide("Cart", "M7 21a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M18 21a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12") }

    /** Utilities. */
    val Zap by lazy { lucide("Zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z") }

    /** Entertainment. */
    val Film by lazy { lucide("Film", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4") }

    /** Housing. */
    val Home by lazy { lucide("Home", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }

    /** Health. */
    val Cross by lazy { lucide("Cross", "M4 9a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h4a1 1 0 0 1 1 1v4a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-4a1 1 0 0 1 1-1h4a2 2 0 0 0 2-2v-2a2 2 0 0 0-2-2h-4a1 1 0 0 1-1-1V4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4a1 1 0 0 1-1 1z") }

    /** Other. */
    val Shapes by lazy { lucide("Shapes", "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z", "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1Z", "M14 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z") }

    /** A category of the user's own. */
    val Tag by lazy { lucide("Tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", dot("M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")) }

    /** Transactions (bottom bar). */
    val Receipt by lazy { lucide("Receipt", "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z", "M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8", "M12 17.5v-11") }

    /** Budgets (bottom bar). */
    val Pie by lazy { lucide("Pie", "M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z", "M21.21 15.89A10 10 0 1 1 8 2.83") }

    /** Add. */
    val Plus by lazy { lucide("Plus", "M5 12h14", "M12 5v14") }

    /** Close. */
    val X by lazy { lucide("X", "M18 6 6 18", "m6 6 12 12") }

    /** Back. */
    val ArrowLeft by lazy { lucide("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }

    /** Selected, done. */
    val Check by lazy { lucide("Check", "M20 6 9 17l-5-5") }

    /** Opens a picker or a collapsed row. */
    val ChevronDown by lazy { lucide("ChevronDown", "m6 9 6 6 6-6") }

    /** Previous month. */
    val ChevronLeft by lazy { lucide("ChevronLeft", "m15 18-6-6 6-6") }

    /** Next month. */
    val ChevronRight by lazy { lucide("ChevronRight", "m9 18 6-6-6-6") }

    /** Date. */
    val Calendar by lazy { lucide("Calendar", "M8 2v4", "M16 2v4", "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M3 10h18") }

    /** Description. */
    val Lines by lazy { lucide("Lines", "M15 18H3", "M17 6H3", "M21 12H3") }

    /** Name (create account). */
    val User by lazy { lucide("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z") }

    /** The app's language (Welcome's chip, Settings). */
    val Globe by lazy { lucide("Globe", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20Z", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20") }

    /** Type an entry in. */
    val Pencil by lazy { lucide("Pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z") }

    /** Email. */
    val Mail by lazy { lucide("Mail", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2Z", "m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7") }

    /** Password, session ended. */
    val Lock by lazy { lucide("Lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z", "M7 11V7a5 5 0 0 1 10 0v4") }

    /** Show password. */
    val Eye by lazy { lucide("Eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z") }

    /** Hide password. */
    val EyeOff by lazy { lucide("EyeOff", "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242", "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20") }

    /** An error. */
    val CircleAlert by lazy { lucide("CircleAlert", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 8v4", "M12 16h.01") }

    /** Entries the server refused. */
    val TriangleAlert by lazy { lucide("TriangleAlert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01") }

    /** Waiting. */
    val Clock by lazy { lucide("Clock", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 6v6l4 2") }

    /** Delete. */
    val Trash by lazy { lucide("Trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2") }

    /** Sign out. */
    val LogOut by lazy { lucide("LogOut", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9") }

    /** Sync now. */
    val Refresh by lazy { lucide("Refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }

    /** No budgets yet. */
    val PiggyBank by lazy { lucide("PiggyBank", "M11 17h3v2a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3a3.16 3.16 0 0 0 2-2h1a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1h-1a5 5 0 0 0-2-4V3a4 4 0 0 0-3.2 1.6l-.3.4H11a6 6 0 0 0-6 6v1a5 5 0 0 0 2 4v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1z", "M16 10h.01", "M2 8v1a2 2 0 0 0 2 2h1") }

    /** About. */
    val Info by lazy { lucide("Info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 16v-4", "M12 8h.01") }

    /** The Add pill's plus, drawn heavier (2.25) as on the boards. */
    val PlusBold by lazy { lucide("PlusBold", "M5 12h14", "M12 5v14", strokeWidth = 2.25f) }

    /** The type selector's arrows and the selected category's check, drawn at 2 as on the boards. */
    val ArrowUpRightBold by lazy { lucide("ArrowUpRightBold", "M7 7h10v10", "M7 17 17 7", strokeWidth = 2f) }

    val ArrowDownLeftBold by lazy { lucide("ArrowDownLeftBold", "M17 7 7 17", "M17 17H7V7", strokeWidth = 2f) }

    val CheckBold by lazy { lucide("CheckBold", "M20 6 9 17l-5-5", strokeWidth = 2f) }

    val Mic by lazy { lucide("Mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3") }

    val MicOff by lazy { lucide("MicOff", "M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7 7 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12") }

    val Download by lazy { lucide("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }

    val Smartphone by lazy { lucide("Smartphone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z", "M12 18h.01") }

    val CircleHelp by lazy { lucide("CircleHelp", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01") }

    val ToggleLeft by lazy { lucide("ToggleLeft", "M8 6h8a6 6 0 0 1 0 12h-8a6 6 0 0 1 0 -12Z", "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z") }

    val Bell by lazy { lucide("Bell", "M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }

    val Cloud by lazy { lucide("Cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z") }

    val MicBold by lazy { lucide("MicBold", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3", strokeWidth = 2f) }
}
