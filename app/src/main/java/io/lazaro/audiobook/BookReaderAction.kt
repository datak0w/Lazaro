package io.lazaro.audiobook

import io.lazaro.actions.ActionResult
import io.lazaro.actions.PendingAction
import io.lazaro.voice.VoiceOptionParser
import io.lazaro.memory.MemoryRepository
import io.lazaro.memory.entity.MemoryCategory
import io.lazaro.voice.TextToSpeechManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookReaderAction @Inject constructor(
    private val bookIntentDetector: BookIntentDetector,
    private val gutenbergRepository: GutenbergRepository,
    private val librivoxRepository: LibrivoxRepository,
    private val libbyHelper: LibbyHelper,
    private val memoryRepository: MemoryRepository,
    private val audiobookPlayer: AudiobookPlayer,
    private val textToSpeechManager: TextToSpeechManager,
) {
    suspend fun tryPrepare(userText: String): ActionResult? {
        if (isLibbyRequest(userText)) {
            return openLibby()
        }
        return when (val intent = bookIntentDetector.detectQuery(userText)) {
            is BookIntent.Read -> prepareRead(intent.titleQuery)
            BookIntent.Continue -> prepareContinue()
            null -> null
        }
    }

    suspend fun prepareRead(titleQuery: String?): ActionResult {
        val gutenberg = gutenbergRepository.searchSpanish(titleQuery, limit = 5)
        val librivox = librivoxRepository.searchSpanish(titleQuery, limit = 5)
        val books = mergeOffers(gutenberg, librivox)

        if (books.isEmpty()) {
            val libbyHint = libbyHelper.hint().orEmpty()
            return ActionResult.Error(
                "No encuentro libros gratis en español${titleQuery?.let { " sobre $it" }.orEmpty()}. " +
                    "Prueba: Don Quijote, La Odisea o Los miserables. $libbyHint".trim(),
            )
        }

        if (books.size == 1) {
            return confirmBook(books.first())
        }

        val options = books.mapIndexed { index, book ->
            val suffix = buildString {
                if (book.hasAudio) append(" (audio)")
                append(" [${book.source.label}]")
            }
            "${index + 1}: ${book.title} de ${book.authors}$suffix"
        }.joinToString(". ")

        val libbyHint = libbyHelper.hint()?.let { " $it" }.orEmpty()

        return ActionResult.NeedsConfirmation(
            prompt = "Encontré ${books.size} libros gratis: $options. Di el número o el título.$libbyHint",
            pendingAction = PendingAction(
                toolName = "select_book",
                args = books.take(5).mapIndexed { index, book ->
                    "candidate_$index" to encodeBook(book)
                }.toMap() + mapOf("query" to (titleQuery.orEmpty())),
            ),
        )
    }

    suspend fun prepareContinue(): ActionResult {
        val progress = loadProgress()
            ?: return ActionResult.Error("No tienes ningún libro a medias. Di: léeme un libro.")

        val book = findBookById(progress.bookId, progress.title)
            ?: return ActionResult.Error("No encuentro el libro que estabas leyendo.")

        return ActionResult.NeedsConfirmation(
            prompt = "¿Sigo leyendo ${book.title} donde lo dejamos? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "read_book",
                args = bookArgs(book, continueFrom = progress.charOffset),
            ),
        )
    }

    suspend fun openLibby(): ActionResult {
        return if (libbyHelper.openApp()) {
            ActionResult.Success("Abro Libby. Con tu carnet de biblioteca puedes pedir audiolibros gratis.")
        } else {
            ActionResult.Error("No tienes Libby instalada. Instálala para audiolibros de biblioteca pública.")
        }
    }

    fun resolveSelection(args: Map<String, String>, selection: String): AudiobookOffer? {
        val candidates = args.filterKeys { it.startsWith("candidate_") }
            .values
            .mapNotNull { decodeBook(it) }

        VoiceOptionParser.parseIndex(selection, candidates.size)?.let { index ->
            if (index in candidates.indices) return candidates[index]
        }

        val normalized = selection.lowercase().trim()
        return candidates.find {
            it.title.lowercase().contains(normalized) || normalized.contains(it.title.lowercase())
        }
    }

    suspend fun confirmSelection(args: Map<String, String>, selection: String): ActionResult {
        val book = resolveSelection(args, selection)
            ?: return ActionResult.Error("No he entendido qué libro quieres. Di el número o el título.")

        return confirmBook(book)
    }

    suspend fun confirmRead(args: Map<String, String>): ActionResult {
        val book = decodeBookFromArgs(args)
            ?: return ActionResult.Error("No tengo el libro listo para leer.")

        return if (book.hasAudio && !args["force_text"].toBoolean()) {
            playAudioBook(book)
        } else {
            readTextBook(book, args["offset"]?.toIntOrNull() ?: 0)
        }
    }

    fun stopPlayback() {
        audiobookPlayer.stop()
        textToSpeechManager.stop()
    }

    private suspend fun findBookById(id: String, title: String): AudiobookOffer? {
        return gutenbergRepository.searchSpanish(title, limit = 10)
            .plus(librivoxRepository.searchSpanish(title, limit = 10))
            .firstOrNull { it.id == id }
    }

    private fun mergeOffers(gutenberg: List<AudiobookOffer>, librivox: List<AudiobookOffer>): List<AudiobookOffer> {
        val map = linkedMapOf<String, AudiobookOffer>()
        for (book in librivox + gutenberg) {
            val key = book.title.lowercase()
            val existing = map[key]
            if (existing == null || (!existing.hasAudio && book.hasAudio)) {
                map[key] = book
            }
        }
        return map.values.toList()
    }

    private suspend fun confirmBook(book: AudiobookOffer): ActionResult {
        val mode = when {
            book.hasAudio -> "Tiene versión en audio."
            book.textUrl != null -> "Lo leeré en voz alta."
            else -> "Lo preparo para ti."
        }
        return ActionResult.NeedsConfirmation(
            prompt = "${book.title}, de ${book.authors}. Fuente: ${book.source.label}. $mode ¿Empezamos? Di sí o no.",
            pendingAction = PendingAction(
                toolName = "read_book",
                args = bookArgs(book, continueFrom = 0),
            ),
        )
    }

    private suspend fun playAudioBook(book: AudiobookOffer): ActionResult {
        val url = when (book.source) {
            AudiobookSource.GUTENBERG -> book.audioUrl
            AudiobookSource.LIBRIVOX -> librivoxRepository.resolveAudioUrl(book)
        } ?: return readTextBook(book, 0)

        saveLastBook(book)
        audiobookPlayer.playUrl(url)
        return ActionResult.Success(
            "Reproduciendo ${book.title} desde ${book.source.label}. Di Lazaro para parar.",
        )
    }

    private suspend fun readTextBook(book: AudiobookOffer, offset: Int): ActionResult {
        val textUrl = book.textUrl
            ?: return ActionResult.Error("${book.title} no tiene texto disponible. Prueba otro libro con audio.")

        val fullText = gutenbergRepository.fetchText(textUrl, maxChars = 500_000)
        if (fullText.isBlank()) {
            return ActionResult.Error("No pude descargar el texto del libro.")
        }

        val safeOffset = offset.coerceIn(0, fullText.lastIndex.coerceAtLeast(0))
        val chunk = fullText.substring(safeOffset).take(CHUNK_SIZE)
        if (chunk.isBlank()) {
            return ActionResult.Success("Ya terminaste ${book.title}. ¿Quieres otro libro?")
        }

        saveProgress(book, safeOffset + chunk.length)
        saveLastBook(book)

        val intro = if (safeOffset == 0) {
            "Empiezo ${book.title}, de ${book.authors}."
        } else {
            "Sigo ${book.title}."
        }

        textToSpeechManager.speak("$intro $chunk")
        return ActionResult.Success(
            "$intro Di continúa leyendo para seguir, o Lazaro para parar.",
        )
    }

    private suspend fun saveProgress(book: AudiobookOffer, offset: Int) {
        memoryRepository.saveMemory(
            key = progressKey(book.id),
            value = "$offset|${book.title}|${book.textUrl.orEmpty()}",
            category = MemoryCategory.PREFERENCE,
            aliases = listOf("progreso libro", "continua leyendo"),
            notes = "Progreso de lectura",
            source = "book_reader",
        )
    }

    private suspend fun saveLastBook(book: AudiobookOffer) {
        memoryRepository.saveMemory(
            key = "book_last_read",
            value = encodeBook(book),
            category = MemoryCategory.PREFERENCE,
            aliases = listOf("ultimo libro", "libro favorito"),
            source = "book_reader",
        )
    }

    private suspend fun loadProgress(): BookProgress? {
        val last = memoryRepository.getMemory("book_last_read")?.value?.let { decodeBook(it) }
            ?: return null
        val raw = memoryRepository.getMemory(progressKey(last.id))?.value ?: return null
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return null
        return BookProgress(
            bookId = last.id,
            title = parts[1],
            charOffset = parts[0].toIntOrNull() ?: 0,
            textUrl = parts[2],
        )
    }

    private fun bookArgs(book: AudiobookOffer, continueFrom: Int): Map<String, String> {
        return mapOf(
            "book_id" to book.id,
            "title" to book.title,
            "authors" to book.authors,
            "source" to book.source.name,
            "text_url" to book.textUrl.orEmpty(),
            "audio_url" to book.audioUrl.orEmpty(),
            "rss_url" to book.rssUrl.orEmpty(),
            "has_audio" to book.hasAudio.toString(),
            "offset" to continueFrom.toString(),
        )
    }

    private fun decodeBookFromArgs(args: Map<String, String>): AudiobookOffer? {
        val id = args["book_id"].orEmpty().ifBlank { return null }
        return AudiobookOffer(
            id = id,
            title = args["title"].orEmpty(),
            authors = args["authors"].orEmpty(),
            source = runCatching { AudiobookSource.valueOf(args["source"].orEmpty()) }
                .getOrDefault(AudiobookSource.GUTENBERG),
            textUrl = args["text_url"].orEmpty().ifBlank { null },
            audioUrl = args["audio_url"].orEmpty().ifBlank { null },
            rssUrl = args["rss_url"].orEmpty().ifBlank { null },
        )
    }

    private fun encodeBook(book: AudiobookOffer): String {
        return listOf(
            book.id,
            book.title,
            book.authors,
            book.source.name,
            book.textUrl.orEmpty(),
            book.audioUrl.orEmpty(),
            book.rssUrl.orEmpty(),
            book.hasAudio.toString(),
        ).joinToString("||")
    }

    private fun decodeBook(raw: String): AudiobookOffer? {
        val parts = raw.split("||")
        if (parts.size < 8) return null
        return AudiobookOffer(
            id = parts[0],
            title = parts[1],
            authors = parts[2],
            source = runCatching { AudiobookSource.valueOf(parts[3]) }
                .getOrDefault(AudiobookSource.GUTENBERG),
            textUrl = parts[4].ifBlank { null },
            audioUrl = parts[5].ifBlank { null },
            rssUrl = parts[6].ifBlank { null },
        )
    }

    private fun progressKey(bookId: String) = "book_progress_$bookId"

    private fun isLibbyRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("libby") ||
            normalized.contains("biblioteca") && normalized.contains("audiolibro")
    }

    companion object {
        private const val CHUNK_SIZE = 3500
    }
}
