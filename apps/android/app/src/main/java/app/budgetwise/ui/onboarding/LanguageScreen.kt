package app.budgetwise.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.budgetwise.AppLanguage
import app.budgetwise.LanguageStore
import app.budgetwise.R
import app.budgetwise.currentAppLanguage
import app.budgetwise.ui.common.BackHeader
import app.budgetwise.ui.common.BrandMark
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.PrimaryButton
import app.budgetwise.ui.common.SageRule
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.bold
import app.budgetwise.ui.theme.extraBold
import app.budgetwise.ui.theme.kitStyle
import app.budgetwise.ui.theme.semibold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(private val store: LanguageStore) : ViewModel() {
    /** Draws the app in [language]; true when the caller has to recreate the activity to show it. */
    fun choose(language: AppLanguage): Boolean {
        store.apply(language)
        return store.needsRecreate
    }

    fun finish() = store.markChosen()
}

/**
 * Picking a language redraws the app in it at once (the activity is recreated), so the user sees the choice before
 * going on. Nothing picked keeps the phone's language. [onBack] is null on first launch, where this is the first screen.
 */
@Composable
fun LanguageRoute(onDone: () -> Unit, onBack: (() -> Unit)?, viewModel: LanguageViewModel = hiltViewModel()) {
    val activity = LocalActivity.current
    val current = currentAppLanguage()
    LanguageScreen(
        selected = current,
        onSelect = { language -> if (language != current && viewModel.choose(language)) activity?.recreate() },
        onDone = {
            viewModel.finish()
            onDone()
        },
        onBack = onBack,
    )
}

/**
 * Both languages, each written in its own script whatever the app's language is, so a reader of either finds theirs.
 * On first launch the brand sits on top and the button says Continue; from Settings or Welcome it has a back arrow
 * and says Done.
 */
@Composable
fun LanguageScreen(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(modifier.fillMaxSize().background(colors.ground).windowInsetsPadding(WindowInsets.navigationBars)) {
        if (onBack != null) {
            BackHeader(title = null, onBack = onBack)
        } else {
            BrandMark(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 24.dp, top = 28.dp), size = 40.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(if (onBack == null) 160.dp else 16.dp))
            Text(
                text = stringResource(R.string.language_title_my),
                style = scriptStyle(AppLanguage.BURMESE, 32).bold,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.language_title_en),
                style = scriptStyle(AppLanguage.ENGLISH, 32).extraBold.copy(letterSpacing = (-0.035).em),
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            SageRule(Modifier.padding(top = 18.dp))
            Column(Modifier.padding(top = 40.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguageOption(
                    language = AppLanguage.BURMESE,
                    other = stringResource(R.string.language_burmese_in_english),
                    otherStyle = scriptStyle(AppLanguage.ENGLISH, 14),
                    selected = selected == AppLanguage.BURMESE,
                    onClick = { onSelect(AppLanguage.BURMESE) },
                )
                LanguageOption(
                    language = AppLanguage.ENGLISH,
                    other = stringResource(R.string.language_english_in_burmese),
                    otherStyle = scriptStyle(AppLanguage.BURMESE, 14),
                    selected = selected == AppLanguage.ENGLISH,
                    onClick = { onSelect(AppLanguage.ENGLISH) },
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBack == null) {
                Text(stringResource(R.string.language_later), style = type.size14, color = colors.muted, textAlign = TextAlign.Center)
            }
            PrimaryButton(
                text = stringResource(if (onBack == null) R.string.language_continue else R.string.language_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** [language]'s own name, in its own script: "မြန်မာ" or "English". */
@Composable
internal fun AppLanguage.ownName(): String = stringResource(
    when (this) {
        AppLanguage.BURMESE -> R.string.language_burmese
        AppLanguage.ENGLISH -> R.string.language_english
    },
)

/** The kit's [role] in [script]'s font and metrics, whatever language the app is drawn in. */
@Composable
internal fun scriptStyle(script: AppLanguage, role: Int): TextStyle {
    val fonts = BudgetWiseTheme.fonts
    return when (script) {
        AppLanguage.BURMESE -> kitStyle(fonts.myanmar, role, burmese = true)
        AppLanguage.ENGLISH -> kitStyle(fonts.latin, role, burmese = false)
    }
}

/** A 76 dp row: the language's own name over its name in the other language, and a round check. */
@Composable
private fun LanguageOption(
    language: AppLanguage,
    other: String,
    otherStyle: TextStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BudgetWiseTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val nameStyle = when (language) {
        AppLanguage.BURMESE -> scriptStyle(language, 20).bold
        AppLanguage.ENGLISH -> scriptStyle(language, 18).semibold
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.5.dp, if (selected) colors.accent else colors.surface, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(language.ownName(), style = nameStyle, color = colors.ink)
            Text(other, style = otherStyle, color = colors.muted)
        }
        if (selected) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(colors.sageFill), contentAlignment = Alignment.Center) {
                Icon(Lucide.CheckBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(14.dp))
            }
        } else {
            Box(Modifier.size(24.dp).border(1.5.dp, colors.outline, CircleShape))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LanguageScreenPreview() {
    BudgetWiseTheme { LanguageScreen(selected = AppLanguage.BURMESE, onSelect = {}, onDone = {}, onBack = null) }
}
