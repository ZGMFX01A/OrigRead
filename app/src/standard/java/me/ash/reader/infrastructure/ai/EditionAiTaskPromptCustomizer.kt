package me.ash.reader.infrastructure.ai

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Standard edition 不加载 Skill，Prompt 始终保持 OrigRead 内置行为。 */
@Singleton
class EditionAiTaskPromptCustomizer @Inject constructor() : AiTaskPromptCustomizer {
    override suspend fun customize(
        task: AiTaskType,
        baseSystemPrompt: String,
    ): AiTaskPromptCustomization = AiTaskPromptCustomization(systemPrompt = baseSystemPrompt)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EditionAiTaskPromptModule {
    @Binds
    abstract fun bindAiTaskPromptCustomizer(
        implementation: EditionAiTaskPromptCustomizer,
    ): AiTaskPromptCustomizer
}
