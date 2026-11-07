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
 * Approved Pocket Peek artwork replaces semantically matching icons. Remaining icons come from the existing
 * boards (Lucide, ISC license). Decorative everywhere: the text next to each one says what it means.
 */
object Lucide {
    /** Settings. */
    val Settings by lazy { PocketPeekIcons.Settings }

    /** Synced. */
    val CloudCheck by lazy { lucide("CloudCheck", "m17 15-5.5 5.5L9 18", "M5 17.743A7 7 0 1 1 15.71 10h1.79a4.5 4.5 0 0 1 1.5 8.742") }

    /** Offline. */
    val CloudOff by lazy { lucide("CloudOff", "m2 2 20 20", "M5.782 5.782A7 7 0 0 0 9 19h8.5a4.5 4.5 0 0 0 1.307-.193", "M21.532 16.5A4.5 4.5 0 0 0 17.5 10h-1.79A7.008 7.008 0 0 0 10 5.07") }

    /** Not synced: the server could not be reached. */
    val CloudAlert by lazy { lucide("CloudAlert", "M12 12v4", "M12 20h.01", "M17 18h.5a1 1 0 0 0 0-9h-1.79A7 7 0 1 0 7 17.708") }

    /** Food and Drink. */
    val Utensils by lazy { PocketPeekIcons.Food }

    /** Transport. */
    val Bus by lazy { PocketPeekIcons.Transport }

    /** Income. */
    val ArrowDownLeft by lazy { PocketPeekIcons.Income }

    /** Expense. */
    val ArrowUpRight by lazy { lucide("ArrowUpRight", "M7 7h10v10", "M7 17 17 7") }

    /** Groceries. */
    val Cart by lazy { PocketPeekIcons.Shopping }

    /** Utilities. */
    val Zap by lazy { lucide("Zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z") }

    /** Entertainment. */
    val Film by lazy { lucide("Film", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4") }

    /** Housing. */
    val Home by lazy { PocketPeekIcons.Home }

    /** Health. */
    val Cross by lazy { PocketPeekIcons.Health }

    /** Other. */
    val Shapes by lazy { lucide("Shapes", "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1Z", "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1Z", "M14 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z") }

    /** A category of the user's own. */
    val Tag by lazy { lucide("Tag", "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z", dot("M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z")) }

    /** Transactions (bottom bar and empty transaction lists). */
    val Transactions by lazy { PocketPeekIcons.Transactions }

    /** A receipt. */
    val Receipt by lazy { PocketPeekIcons.Receipt }

    /** A scanned receipt's entries, as one row of the list. */
    val ReceiptText by lazy { PocketPeekIcons.Receipt }

    /** Budgets (bottom bar). */
    val Pie by lazy { PocketPeekIcons.Budget }

    /** Add. */
    val Plus by lazy { PocketPeekIcons.Add }

    /** Close. */
    val X by lazy { PocketPeekIcons.Close }

    /** Back. */
    val ArrowLeft by lazy { lucide("ArrowLeft", "m12 19-7-7 7-7", "M19 12H5") }

    /** Selected, done. */
    val Check by lazy { PocketPeekIcons.Check }

    /** Arrow right: a primary button that goes on to the next step. */
    val ArrowRightBold by lazy { lucide("ArrowRightBold", "M5 12h14", "m13 6 6 6-6 6", strokeWidth = 2.25f) }

    /** Arrow up: raise the month's budget. */
    val ArrowUpBold by lazy { lucide("ArrowUpBold", "M12 19V5", "m5 12 7-7 7 7", strokeWidth = 2.25f) }

    /** Wallet: Everything else, the part of a month's budget no category has. */
    val Wallet by lazy { PocketPeekIcons.Wallet }

    /** Opens a picker or a collapsed row. */
    val ChevronDown by lazy { lucide("ChevronDown", "m6 9 6 6 6-6") }

    /** Previous month. */
    val ChevronLeft by lazy { lucide("ChevronLeft", "m15 18-6-6 6-6") }

    /** Next month. */
    val ChevronRight by lazy { PocketPeekIcons.ChevronRight }

    /** Date. */
    val Calendar by lazy { PocketPeekIcons.Calendar }

    /** Description. */
    val Lines by lazy { lucide("Lines", "M15 18H3", "M17 6H3", "M21 12H3") }

    /** Name (create account). */
    val User by lazy { lucide("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z") }

    /** The app's language (Welcome's chip, Settings). */
    val Globe by lazy { lucide("Globe", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20Z", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20") }

    /** Type an entry in. */
    val Pencil by lazy { lucide("Pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z") }

    /** The Add sheet's Type it, and an entry that was typed in. */
    val Keyboard by lazy { lucide("Keyboard", "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z", "M6 9h.01", "M10 9h.01", "M14 9h.01", "M18 9h.01", "M8 15h8") }

    /** Take a photo of a receipt. */
    val Camera by lazy { lucide("Camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z") }

    /** Choose a photo from the phone. */
    val Image by lazy { lucide("Image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z", "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }

    /** A scanned receipt: the "Scanned" tag and the couldn't-read card. */
    val ScanLine by lazy { lucide("ScanLine", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10") }

    /** The camera's light, on. */
    val Flashlight by lazy { lucide("Flashlight", "M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12z", "M6 6h12", "M12 12h.01") }

    /** The camera's light, off. */
    val FlashlightOff by lazy { lucide("FlashlightOff", "M16 16v4a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4", "M7 2h11v4c0 2-2 2-2 4v1", "M11 6h7", "m2 2 20 20") }

    /** Done: entries added. */
    val CircleCheck by lazy { lucide("CircleCheck", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "m9 12 2 2 4-4") }

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
    val PiggyBank by lazy { PocketPeekIcons.Budget }

    /** About. */
    val Info by lazy { lucide("Info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 16v-4", "M12 8h.01") }

    /** The Add pill's plus, using the approved Add artwork. */
    val PlusBold by lazy { PocketPeekIcons.Add }

    /** A receipt item's count stepper, with [PlusBold]. */
    val MinusBold by lazy { lucide("MinusBold", "M5 12h14", strokeWidth = 2.25f) }

    /** The type selector's icons and the selected category's check. */
    val ArrowUpRightBold by lazy { lucide("ArrowUpRightBold", "M7 7h10v10", "M7 17 17 7", strokeWidth = 2f) }

    val ArrowDownLeftBold by lazy { PocketPeekIcons.Income }

    val CheckBold by lazy { PocketPeekIcons.Check }

    val Mic by lazy { PocketPeekIcons.Microphone }

    val MicOff by lazy { lucide("MicOff", "M12 19v3", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M16.95 16.95A7 7 0 0 1 5 12v-2", "M18.89 13.23A7 7 0 0 0 19 12v-2", "m2 2 20 20", "M9 9v3a3 3 0 0 0 5.12 2.12") }

    val Download by lazy { lucide("Download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }

    val Smartphone by lazy { lucide("Smartphone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z", "M12 18h.01") }

    val CircleHelp by lazy { lucide("CircleHelp", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01") }

    val ToggleLeft by lazy { lucide("ToggleLeft", "M8 6h8a6 6 0 0 1 0 12h-8a6 6 0 0 1 0 -12Z", "M6 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z") }

    val Bell by lazy { lucide("Bell", "M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326") }

    val Cloud by lazy { lucide("Cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z") }

    val MicBold by lazy { PocketPeekIcons.Microphone }

    val WifiOff by lazy { lucide("WifiOff", "M12 20h.01", "M8.5 16.429a5 5 0 0 1 7 0", "M5 12.859a10 10 0 0 1 5.17-2.69", "M19 12.859a10 10 0 0 0-2.007-1.523", "M2 8.82a15 15 0 0 1 4.177-2.643", "M22 8.82a15 15 0 0 0-11.288-3.764", "m2 2 20 20") }
}
