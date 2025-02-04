package com.grimels.keristra.core.ai.anthropic

import dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicChatModelName
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.model.voyageai.VoyageAiEmbeddingModel
import dev.langchain4j.model.voyageai.VoyageAiEmbeddingModelName
import dev.langchain4j.rag.content.retriever.ContentRetriever
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import dev.langchain4j.service.AiServices
import dev.langchain4j.store.embedding.EmbeddingStore
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

@Configuration
class AnthropicConfig {

//    @Bean
//    fun embeddingStoreModel(): EmbeddingModel {
//        return AllMiniLmL6V2EmbeddingModel()
//    }

    @Bean
    fun embeddingStoreModel(@Value("\${voyage.api.key}") apiKey: String): EmbeddingModel {
        return VoyageAiEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(VoyageAiEmbeddingModelName.VOYAGE_3)
            .build();
    }

    @Bean
    fun commandLineRunner(
        embeddingStoreModel: EmbeddingModel,
        store: EmbeddingStore<TextSegment>,
        resourceLoader: ResourceLoader
    ): CommandLineRunner {
        return CommandLineRunner { args: Array<String?>? ->
            this.loadDocuments(
                embeddingStoreModel,
                store,
                resourceLoader,
                listOf("classpath:DMNCustom.txt", "classpath:DMNCustom2.txt"),
                1000,
                200
            )
//            this.loadDocument(embeddingStoreModel, store, resourceLoader, "classpath:DMNCustom2.txt", 256, 0)
//            this.loadDocument(embeddingStoreModel, store, resourceLoader, "classpath:DMNSpec.xsd")
//            this.loadDocument(embeddingStoreModel, store, resourceLoader, "classpath:data-set/tuning.jsonl")
        }
    }

    fun loadDocuments(
        embeddingStoreModel: EmbeddingModel,
        store: EmbeddingStore<TextSegment>,
        resourceLoader: ResourceLoader,
        paths: List<String>,
        maxSegmentSize: Int,
        maxOverlap: Int
    ) {
        val docs = paths
            .map { resourceLoader.getResource(it) }
            .map { loadDocument(it.file.toPath()) }

        val splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlap)
        val ingestor = EmbeddingStoreIngestor.builder()
            .embeddingStore(store)
            .embeddingModel(embeddingStoreModel)
            .documentSplitter(splitter)
            .build()

        try {
            ingestor.ingest(docs)
            println("Ingested document: $paths")
        } catch (e: Exception) {
            throw RuntimeException("Failed to ingest document: $paths. Reason: ${e.message}")
        }
    }

    @Bean
    fun createModel(@Value("\${anthropic.api.key}") apiKey: String): ChatLanguageModel {
        return AnthropicChatModel.builder()
            .apiKey(apiKey)
            .modelName(AnthropicChatModelName.CLAUDE_3_SONNET_20240229)
            .maxTokens(4096)
            .cacheSystemMessages(true)
            .build()
    }

    @Bean
    fun createAnthropicService(chatLanguageModel: ChatLanguageModel, retriever: ContentRetriever): AnthropicDMNService {
        return AiServices.builder(AnthropicDMNService::class.java)
            .chatLanguageModel(chatLanguageModel)
            .contentRetriever(retriever)
            .build()
    }

    @Bean
    fun createEmbeddingStore(): EmbeddingStore<TextSegment> {
        return InMemoryEmbeddingStore()
    }

    @Bean
    fun createRetriever(
        embeddingStoreModel: EmbeddingModel,
        embeddingStore: EmbeddingStore<TextSegment>
    ): ContentRetriever {
        return EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingStoreModel)
            .maxResults(4)
            .minScore(0.75)
            .build()
    }

}
