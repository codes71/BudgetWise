package app.budgetwise.di

import android.content.Context
import androidx.work.WorkManager
import app.budgetwise.DefaultOnDeviceSpeech
import app.budgetwise.DefaultVoiceAvailability
import app.budgetwise.DefaultVoiceParser
import app.budgetwise.OnDeviceSpeech
import app.budgetwise.VoiceAvailability
import app.budgetwise.VoiceParser
import app.budgetwise.asr.DefaultModelManager
import app.budgetwise.asr.MemoryGuard
import app.budgetwise.asr.ModelManager
import app.budgetwise.asr.OnnxSpeechToText
import app.budgetwise.voice.AudioRecordMicrophone
import app.budgetwise.voice.Microphone
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Voice entry: the downloaded speech models, the engine that runs them, the microphone and the server's reading. */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides
    @Singleton
    fun modelManager(@ApplicationContext context: Context): ModelManager =
        DefaultModelManager(context, WorkManager.getInstance(context))

    /**
     * One engine per process: the loaded models are about 1 GB. Nothing native loads until the first use, and the
     * [MemoryGuard] frees them when Android asks the app to trim memory in the background.
     */
    @Provides
    @Singleton
    fun speechToText(@ApplicationContext context: Context, models: ModelManager): OnnxSpeechToText =
        OnnxSpeechToText.create(models).also { context.registerComponentCallbacks(MemoryGuard(it)) }

    @Provides
    fun microphone(): Microphone = AudioRecordMicrophone()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceBindingsModule {
    @Binds
    abstract fun voiceParser(impl: DefaultVoiceParser): VoiceParser

    @Binds
    abstract fun onDeviceSpeech(impl: DefaultOnDeviceSpeech): OnDeviceSpeech
}

/** Its own module, so a test can replace the phone's memory check without the rest of voice. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceAvailabilityModule {
    @Binds
    abstract fun voiceAvailability(impl: DefaultVoiceAvailability): VoiceAvailability
}
