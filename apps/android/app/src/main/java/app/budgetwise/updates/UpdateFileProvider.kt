package app.budgetwise.updates

import androidx.core.content.FileProvider

/** App-owned provider subclass, as required for reliable FileProvider initialization across devices. */
class UpdateFileProvider : FileProvider()
