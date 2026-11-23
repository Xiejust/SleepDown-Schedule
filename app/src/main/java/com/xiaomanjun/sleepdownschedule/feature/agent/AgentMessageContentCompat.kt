package com.xiaomanjun.sleepdownschedule.feature.agent

/**
 * Persisted agent message content is part of the backup protocol. Even though the in-app AI
 * assistant has been removed, old backups may still carry these markers, so the encoding must
 * stay byte-compatible.
 */
data class AgentPersistedMessageContent(
    val text: String,
    val attachmentFileName: String? = null,
    val attachmentFileNames: List<String> = listOfNotNull(attachmentFileName)
)

private val AgentImageMarkerRegex =
    Regex("""^\[\[agent_image:([A-Za-z0-9._-]+)]]\r?\n?""")

fun agentMessageContent(text: String, attachmentFileName: String?): String {
    return agentMessageContent(text, listOfNotNull(attachmentFileName?.takeIf { it.isNotBlank() }))
}

fun agentMessageContent(text: String, attachmentFileNames: List<String>): String =
    attachmentFileNames.joinToString("") { name ->
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "附件文件名无效" }
        "[[agent_image:$name]]\n"
    } + text

fun parseAgentMessageContent(content: String): AgentPersistedMessageContent {
    val names = mutableListOf<String>()
    var text = content
    while (true) {
        val match = AgentImageMarkerRegex.find(text) ?: break
        names += match.groupValues[1]
        text = text.removeRange(match.range)
    }
    return AgentPersistedMessageContent(text, names.firstOrNull(), names)
}

private val ManagedAgentAttachmentName =
    Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(?:jpg|png|webp)$""")

/** Attachment files written by the retired assistant; only these exact names may be cleaned up. */
fun orphanedAgentAttachmentNames(
    existingNames: Set<String>,
    referencedNames: Set<String>
): Set<String> = existingNames.filterTo(mutableSetOf()) { fileName ->
    ManagedAgentAttachmentName.matches(fileName) && fileName !in referencedNames
}
