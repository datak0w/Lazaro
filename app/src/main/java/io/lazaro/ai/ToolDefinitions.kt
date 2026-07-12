package io.lazaro.ai

import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool

object ToolDefinitions {
    val all: List<Tool> = listOf(
        Tool(
            functionDeclarations = listOf(
                FunctionDeclaration(
                    name = "navigate_to",
                    description = "Inicia navegación peatonal en Google Maps hacia un destino.",
                    parameters = listOf(
                        Schema.str("destination", "Dirección o nombre del lugar de destino."),
                    ),
                    requiredParameters = listOf("destination"),
                ),
                FunctionDeclaration(
                    name = "where_am_i",
                    description = "Obtiene la ubicación actual del usuario y la describe.",
                    parameters = emptyList(),
                    requiredParameters = emptyList(),
                ),
                FunctionDeclaration(
                    name = "web_search",
                    description = "Busca información actual en internet (clima, horarios, noticias, etc.).",
                    parameters = listOf(
                        Schema.str("query", "Consulta de búsqueda en lenguaje natural."),
                    ),
                    requiredParameters = listOf("query"),
                ),
                FunctionDeclaration(
                    name = "read_messages",
                    description = "Lee los mensajes pendientes de WhatsApp y otras apps.",
                    parameters = emptyList(),
                    requiredParameters = emptyList(),
                ),
                FunctionDeclaration(
                    name = "make_call",
                    description = "Llama a un contacto del teléfono.",
                    parameters = listOf(
                        Schema.str("contact_name", "Nombre del contacto a llamar."),
                    ),
                    requiredParameters = listOf("contact_name"),
                ),
                FunctionDeclaration(
                    name = "reply_message",
                    description = "Responde por WhatsApp a un contacto. Si no se indica destinatario, responde al último mensaje leído.",
                    parameters = listOf(
                        Schema.str("recipient", "Nombre del destinatario. Opcional si acaba de leer mensajes."),
                        Schema.str("message", "Texto del mensaje a enviar."),
                    ),
                    requiredParameters = listOf("message"),
                ),
                FunctionDeclaration(
                    name = "play_media",
                    description = "Reproduce o busca música, vídeo, radio, noticias o podcast. Puede abrir apps (Spotify, YouTube…) o buscar artista/canción/tema concreto.",
                    parameters = listOf(
                        Schema.str("media_type", "music, news, radio, podcast o video. Opcional si se indica query."),
                        Schema.str("query", "Artista, canción, tema o vídeo a buscar, ej: Motorhead, Ace of Spades."),
                        Schema.str("app", "App destino: spotify, youtube, youtube music, deezer, etc."),
                    ),
                    requiredParameters = emptyList(),
                ),
                FunctionDeclaration(
                    name = "plan_transit_route",
                    description = "Planifica ruta completa en transporte público desde la ubicación actual hasta un destino (líneas, transbordos, horarios). Abre Google Maps.",
                    parameters = listOf(
                        Schema.str("destination", "Destino: dirección, lugar o alias guardado (casa, trabajo)."),
                    ),
                    requiredParameters = listOf("destination"),
                ),
                FunctionDeclaration(
                    name = "find_transit",
                    description = "Busca la parada o estación de transporte público más cercana (bus, metro, tren, tranvía) y ofrece guiar al usuario a pie hasta allí.",
                    parameters = listOf(
                        Schema.str("transit_type", "any, bus, metro, train o tram. Default any."),
                    ),
                    requiredParameters = emptyList(),
                ),
                FunctionDeclaration(
                    name = "save_memory",
                    description = "Guarda un dato recurrente del cliente (dirección casa, teléfono hermano, preferencia radio, etc.).",
                    parameters = listOf(
                        Schema.str("key", "Clave identificadora, ej: home_address, brother_phone"),
                        Schema.str("value", "Valor a guardar"),
                        Schema.str("category", "address, contact, preference, place, custom"),
                        Schema.str("aliases", "Alias separados por barra vertical, ej: casa|mi casa"),
                    ),
                    requiredParameters = listOf("key", "value"),
                ),
                FunctionDeclaration(
                    name = "create_skill",
                    description = "Crea un skill personalizado: frase del usuario dispara una acción.",
                    parameters = listOf(
                        Schema.str("name", "Nombre del skill, ej: Radio COPE"),
                        Schema.str("trigger_phrases", "Frases separadas por barra vertical"),
                        Schema.str("action_type", "open_app, call_phone, navigate, open_url"),
                        Schema.str("action_payload", "JSON o valor: package, teléfono, dirección, url"),
                        Schema.str("skill_description", "Descripción breve"),
                    ),
                    requiredParameters = listOf("name", "trigger_phrases", "action_type", "action_payload"),
                ),
                FunctionDeclaration(
                    name = "recall_memory",
                    description = "Recupera un dato guardado del cliente.",
                    parameters = listOf(
                        Schema.str("key", "Clave o alias del dato"),
                    ),
                    requiredParameters = listOf("key"),
                ),
                FunctionDeclaration(
                    name = "get_location_trail",
                    description = "Describe los lugares recientes visitados (útil si el usuario está perdido).",
                    parameters = listOf(
                        Schema.str("hours", "Horas hacia atrás, default 6"),
                    ),
                    requiredParameters = emptyList(),
                ),
            ),
        ),
    )
}
