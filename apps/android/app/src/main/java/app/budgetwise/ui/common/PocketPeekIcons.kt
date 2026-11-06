package app.budgetwise.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Approved Folded Pocket artwork from brand/icons, with inherited callsite tint and a 24-unit viewport. */
private fun pocketPeekIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            paths.forEach { path ->
                addPath(
                    pathData = addPathNodes(path),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        .build()

/** All 20 approved icons. Only icons with matching semantics replace existing artwork. */
internal object PocketPeekIcons {
    val Home by lazy {
        pocketPeekIcon(
            "PocketPeek.Home",
            "m3 10 9-7 9 7v8a2 2 0 0 1-2 2h-3v-7H8v7H5a2 2 0 0 1-2-2Z",
        )
    }

    val Transactions by lazy {
        pocketPeekIcon(
            "PocketPeek.Transactions",
            "M5 4h14v14l-3 2-4-2-4 2-3-2Z",
            "M8 8h5m3 0h.01M8 12h5m3 0h.01",
        )
    }

    val Budget by lazy {
        pocketPeekIcon(
            "PocketPeek.Budget",
            "M3 7a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3Z",
            "M3 10h18M10 10v10m6-10v10",
        )
    }

    val Receipt by lazy {
        pocketPeekIcon(
            "PocketPeek.Receipt",
            "M6 3h12v18l-3-2-3 2-3-2-3 2Z",
            "M9 7h6m-6 4h6m-6 4h3",
        )
    }

    val Microphone by lazy {
        pocketPeekIcon(
            "PocketPeek.Microphone",
            "M12 3H12A3 3 0 0 1 15 6V11A3 3 0 0 1 12 14H12A3 3 0 0 1 9 11V6A3 3 0 0 1 12 3Z",
            "M6 10v1a6 6 0 0 0 12 0v-1m-6 7v4m-3 0h6",
        )
    }

    val Add by lazy {
        pocketPeekIcon(
            "PocketPeek.Add",
            "M12 5v14M5 12h14",
        )
    }

    val Search by lazy {
        pocketPeekIcon(
            "PocketPeek.Search",
            "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0Z",
            "m15.5 15.5 4.5 4.5",
        )
    }

    val Settings by lazy {
        pocketPeekIcon(
            "PocketPeek.Settings",
            "M4 6h3m4 0h9M4 12h9m4 0h3M4 18h5m4 0h7",
            "M7 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
            "M13 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
            "M9 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        )
    }

    val Wallet by lazy {
        pocketPeekIcon(
            "PocketPeek.Wallet",
            "M4 7V5a2 2 0 0 1 2-2h12v4M4 7h14a2 2 0 0 1 2 2v10H6a2 2 0 0 1-2-2Z",
            "M20 11h-5a2 2 0 0 0 0 4h5",
        )
    }

    val Food by lazy {
        pocketPeekIcon(
            "PocketPeek.Food",
            "M4 13h13a6 6 0 0 1-6 7H10a6 6 0 0 1-6-7Zm15-3V4m-2 0v3a2 2 0 0 0 4 0V4M7 4v5m4-5v5",
        )
    }

    val Transport by lazy {
        pocketPeekIcon(
            "PocketPeek.Transport",
            "M9 3H15A4 4 0 0 1 19 7V15A4 4 0 0 1 15 19H9A4 4 0 0 1 5 15V7A4 4 0 0 1 9 3Z",
            "M5 10h14M8 19v2m8-2v2M8 14h.01M16 14h.01",
        )
    }

    val Shopping by lazy {
        pocketPeekIcon(
            "PocketPeek.Shopping",
            "M5 8h14l1 12H4Zm4 0V6a3 3 0 0 1 6 0v2",
        )
    }

    val Health by lazy {
        pocketPeekIcon(
            "PocketPeek.Health",
            "M9 4h6v5h5v6h-5v5H9v-5H4V9h5Z",
        )
    }

    val Education by lazy {
        pocketPeekIcon(
            "PocketPeek.Education",
            "M12 5Q7 2 3 5v14q4-3 9 0 5-3 9 0V5q-4-3-9 0Zm0 0v14",
        )
    }

    val Income by lazy {
        pocketPeekIcon(
            "PocketPeek.Income",
            "M4 12h16v5q0 2-2 3l-6 2-6-2q-2-1-2-3ZM12 2v7M8.5 5.5h7",
        )
    }

    val Transfer by lazy {
        pocketPeekIcon(
            "PocketPeek.Transfer",
            "M3 5h9v6l-4.5 3L3 11Zm9 8h9v6l-4.5 3-4.5-3Z",
            "M16 5h3v4M5 18v3h3",
        )
    }

    val Calendar by lazy {
        pocketPeekIcon(
            "PocketPeek.Calendar",
            "M7 5H17A3 3 0 0 1 20 8V18A3 3 0 0 1 17 21H7A3 3 0 0 1 4 18V8A3 3 0 0 1 7 5Z",
            "M8 3v4m8-4v4M4 10h16M8 14h2m4 0h2m-8 3h2",
        )
    }

    val Close by lazy {
        pocketPeekIcon(
            "PocketPeek.Close",
            "m6 6 12 12M6 18 18 6",
        )
    }

    val Check by lazy {
        pocketPeekIcon(
            "PocketPeek.Check",
            "m5 12 4.5 4.5L19 7",
        )
    }

    val ChevronRight by lazy {
        pocketPeekIcon(
            "PocketPeek.ChevronRight",
            "m9 5 7 7-7 7",
        )
    }

}
