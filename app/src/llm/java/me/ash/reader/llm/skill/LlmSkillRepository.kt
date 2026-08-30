package me.ash.reader.llm.skill

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM edition Skill 仓储。
 *
 * 支持独立 SKILL.md 或 zip Skill 包；包中的脚本永不执行，二进制资源也不会进入模型上下文。
 */
@Singleton
class LlmSkillRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val stateFile = context.filesDir.resolve("llm-skills/state.json")
    private val stateBackupFile = context.filesDir.resolve("llm-skills/state.json.bak")
    private val localSkillDir = context.filesDir.resolve("llm-skills/local")
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<LlmSkillState> = _state.asStateFlow()

    fun current(): LlmSkillState = _state.value

    fun enabledSkills(): List<LlmSkillRecord> = current().skills.filter(LlmSkillRecord::enabled)

    fun skill(id: String?): LlmSkillRecord? =
        id?.let { value -> current().skills.firstOrNull { it.id == value } }

    fun activeSkill(id: String?): LlmSkillRecord? = skill(id)?.takeIf(LlmSkillRecord::enabled)

    fun boundSkill(task: LlmSkillTask): LlmSkillRecord? =
        activeSkill(current().bindings.skillId(task))

    suspend fun import(uri: Uri): LlmSkillImportResult =
        withContext(Dispatchers.IO) {
            val displayName = displayName(uri)
            val bytes = readBounded(uri)
            val imported =
                if (displayName.endsWith(".zip", ignoreCase = true) || bytes.isZip()) {
                    parseZip(bytes, displayName)
                } else {
                    parseStandalone(bytes)
                }
            install(imported, sourceFileName = null)
        }

    /**
     * 将应用内编辑的完整 SKILL.md 保存为真实 app-private Markdown 文件并安装。
     * Skill 唯一 ID 仍来自 frontmatter `name`；文件名只用于本地源文件管理。
     */
    suspend fun createLocal(
        fileName: String,
        markdown: String,
    ): LlmSkillImportResult =
        withContext(Dispatchers.IO) {
            val normalizedFileName = normalizeLocalFileName(fileName)
            val imported = parseStandalone(markdown.toByteArray(Charsets.UTF_8))
            val conflicting =
                current().skills.firstOrNull {
                    it.sourceFileName == normalizedFileName && it.id != imported.parsed.name
                }
            if (conflicting != null) {
                throw LlmSkillFormatException("文件名已被 Skill ${conflicting.id} 使用")
            }

            localSkillDir.mkdirs()
            val target = localSkillDir.resolve(normalizedFileName)
            val temp = localSkillDir.resolve(".$normalizedFileName.tmp")
            temp.writeText(markdown)
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            install(imported, sourceFileName = normalizedFileName)
        }

    @Synchronized
    fun setEnabled(skillId: String, enabled: Boolean) {
        val old = current()
        if (old.skills.none { it.id == skillId }) return
        val next = old.copy(
            skills = old.skills.map { if (it.id == skillId) it.copy(enabled = enabled) else it }
        )
        updateState(next)
    }

    @Synchronized
    fun delete(skillId: String) {
        val old = current()
        if (old.skills.none { it.id == skillId }) return
        var bindings = old.bindings
        LlmSkillTask.entries.forEach { task ->
            if (bindings.skillId(task) == skillId) bindings = bindings.withBinding(task, null)
        }
        old.skills.firstOrNull { it.id == skillId }?.sourceFileName?.let { sourceFileName ->
            runCatching { localSkillDir.resolve(sourceFileName).delete() }
        }
        updateState(old.copy(skills = old.skills.filterNot { it.id == skillId }, bindings = bindings))
    }

    @Synchronized
    fun setBinding(task: LlmSkillTask, skillId: String?) {
        val normalized = skillId?.takeIf { activeSkill(it) != null }
        updateState(current().copy(bindings = current().bindings.withBinding(task, normalized)))
    }

    /** 为 Prompt 注入生成稳定内容；Skill 停用/删除时安全回退为空。 */
    fun instructionFor(skillId: String?): String? =
        activeSkill(skillId)?.instructionBundle()?.takeIf(String::isNotBlank)

    /** 完整配置备份只保存可执行的结构化 Skill 状态，不依赖 app-private 原始 Markdown 文件。 */
    fun exportBackupState(): String = encodeState(current())

    fun validateBackupState(raw: String) {
        validateState(decodeState(raw))
    }

    /**
     * 从完整配置恢复 Skill 与任务绑定。恢复后的 Skill 不再引用旧设备私有文件名，避免留下不存在的文件句柄。
     */
    @Synchronized
    fun restoreBackupState(raw: String) {
        val decoded = decodeState(raw)
        validateState(decoded)
        val portable =
            decoded.copy(
                skills = decoded.skills.map { it.copy(sourceFileName = null) },
            )
        updateState(portable)
    }

    private fun parseStandalone(bytes: ByteArray): ImportedSkill {
        val markdown = bytes.toString(Charsets.UTF_8)
        val parsed = LlmSkillParser.parse(markdown)
        return ImportedSkill(parsed, markdown, emptyList(), hasScripts = false)
    }

    private fun parseZip(bytes: ByteArray, displayName: String): ImportedSkill {
        val entries = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) throw LlmSkillFormatException("Skill 包文件数量过多")
                val path = normalizeArchivePath(entry.name)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw LlmSkillFormatException("Skill 包解压后超过 ${MAX_UNCOMPRESSED_BYTES / 1_000_000} MB")
                    }
                    output.write(buffer, 0, read)
                }
                if (entries.put(path, output.toByteArray()) != null) {
                    throw LlmSkillFormatException("Skill 包存在重复路径：$path")
                }
            }
        }
        val skillPaths = entries.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
        if (skillPaths.size != 1) throw LlmSkillFormatException("Skill 包必须且只能包含一个 SKILL.md")
        val skillPath = skillPaths.single()
        val root = skillPath.removeSuffix("SKILL.md").trimEnd('/')
        val markdown = entries.getValue(skillPath).toString(Charsets.UTF_8)
        val parsed = LlmSkillParser.parse(markdown)
        if (root.isNotBlank() && root.substringAfterLast('/') != parsed.name) {
            throw LlmSkillFormatException("SKILL.md name 必须与所在目录名一致")
        }
        if (root.isBlank()) {
            val archiveBase = displayName.substringBeforeLast('.').lowercase()
            if (archiveBase.isNotBlank() && archiveBase != parsed.name) {
                // 根目录 SKILL.md 在移动端很常见；不拒绝，仅不把 zip 文件名当协议字段。
            }
        }
        val resources = mutableListOf<LlmSkillResource>()
        var textResourceCharacters = 0
        var hasScripts = false
        entries.forEach { (path, content) ->
            if (path == skillPath) return@forEach
            val relative = if (root.isBlank()) path else path.removePrefix("$root/")
            if (relative.startsWith("scripts/")) {
                hasScripts = true
                return@forEach
            }
            if (!relative.isSafeTextResource()) return@forEach
            val text = content.toString(Charsets.UTF_8)
            textResourceCharacters += text.length
            if (text.length > MAX_RESOURCE_CHARACTERS || textResourceCharacters > MAX_TOTAL_RESOURCE_CHARACTERS) {
                throw LlmSkillFormatException("Skill 文本资源过大")
            }
            resources += LlmSkillResource(path = relative, content = text)
        }
        return ImportedSkill(parsed, markdown, resources, hasScripts)
    }

    @Synchronized
    private fun install(
        imported: ImportedSkill,
        sourceFileName: String?,
    ): LlmSkillImportResult {
        val old = current()
        val previous = old.skills.firstOrNull { it.id == imported.parsed.name }
        val now = System.currentTimeMillis()
        val record =
            LlmSkillRecord(
                id = imported.parsed.name,
                description = imported.parsed.description,
                enabled = previous?.enabled ?: true,
                instructions = imported.parsed.instructions,
                resources = imported.resources,
                sourceFileName = sourceFileName,
                license = imported.parsed.license,
                compatibility = imported.parsed.compatibility,
                allowedTools = imported.parsed.allowedTools,
                metadata = imported.parsed.metadata,
                hasScripts = imported.hasScripts,
                contentHash = LlmSkillParser.contentHash(imported.markdown, imported.resources),
                installedAt = previous?.installedAt ?: now,
                updatedAt = now,
            )
        val nextSkills = (old.skills.filterNot { it.id == record.id } + record).sortedBy(LlmSkillRecord::id)
        updateState(old.copy(skills = nextSkills))
        previous?.sourceFileName
            ?.takeIf { it != sourceFileName }
            ?.let { previousFileName -> runCatching { localSkillDir.resolve(previousFileName).delete() } }
        return LlmSkillImportResult(record, replaced = previous != null)
    }

    private fun readBounded(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw LlmSkillFormatException("无法读取所选 Skill 文件")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) throw LlmSkillFormatException("Skill 文件超过 ${MAX_IMPORT_BYTES / 1_000_000} MB")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** 本地文件名只允许单层名称；自动补 `.md`，避免用户把路径写进应用私有目录。 */
    private fun normalizeLocalFileName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.length > 80) {
            throw LlmSkillFormatException("文件名必须为 1-80 个字符")
        }
        if (
            trimmed == "." ||
                trimmed == ".." ||
                trimmed.any { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            throw LlmSkillFormatException("文件名不能包含路径分隔符或控制字符")
        }
        return if (trimmed.endsWith(".md", ignoreCase = true)) trimmed else "$trimmed.md"
    }

    private fun normalizeArchivePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isBlank() || normalized.split('/').any { it == ".." || it.isBlank() }) {
            throw LlmSkillFormatException("Skill 包包含不安全路径")
        }
        return normalized
    }

    private fun ByteArray.isZip(): Boolean = size >= 4 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()

    private fun String.isSafeTextResource(): Boolean {
        val lower = lowercase()
        // Agent Skills 允许在 Skill 根目录或自定义子目录放 REFERENCE.md、领域文档等附加文件。
        // Android 首期只持久化安全文本；scripts 已在上层单独拦截，二进制资源不进入模型上下文。
        return SAFE_TEXT_EXTENSIONS.any(lower::endsWith)
    }

    private fun readState(): LlmSkillState {
        if (!stateFile.exists()) return LlmSkillState()
        runCatching { decodeState(stateFile.readText()).also(::validateState) }
            .getOrNull()
            ?.let { return it }

        // 主文件损坏时只读上一份可解析快照；绝不在初始化阶段拿默认值反写覆盖损坏原件。
        return runCatching {
                if (!stateBackupFile.exists()) return@runCatching LlmSkillState()
                decodeState(stateBackupFile.readText()).also(::validateState)
            }
            .getOrDefault(LlmSkillState())
    }

    private fun updateState(next: LlmSkillState) {
        persist(next)
        _state.value = next
    }

    private fun persist(state: LlmSkillState) {
        validateState(state)
        val parent = requireNotNull(stateFile.parentFile) { "Skill state file 缺少父目录" }
        parent.mkdirs()
        if (stateFile.exists()) {
            val previous = stateFile.readText()
            if (runCatching { decodeState(previous).also(::validateState) }.isSuccess) {
                stateBackupFile.writeText(previous)
            }
        }
        val temp = parent.resolve(stateFile.name + ".tmp")
        temp.writeText(encodeState(state))
        if (!temp.renameTo(stateFile)) {
            stateFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun validateState(state: LlmSkillState) {
        require(state.skills.size <= MAX_SKILLS) { "Skill 数量超过上限 $MAX_SKILLS" }
        require(state.skills.map(LlmSkillRecord::id).distinct().size == state.skills.size) {
            "Skill 状态包含重复 ID"
        }
        val ids = state.skills.mapTo(hashSetOf(), LlmSkillRecord::id)
        state.skills.forEach { skill ->
            require(skill.id.matches(VALID_SKILL_ID)) { "Skill ID 无效：${skill.id}" }
            require(skill.description.length <= 1024) { "Skill description 过长：${skill.id}" }
            require(skill.instructions.isNotBlank() && skill.instructions.length <= MAX_BACKUP_INSTRUCTION_CHARACTERS) {
                "Skill 指令大小无效：${skill.id}"
            }
            require(skill.resources.size <= MAX_ARCHIVE_ENTRIES) { "Skill 文本资源数量过多：${skill.id}" }
            require(skill.resources.sumOf { it.content.length.toLong() } <= MAX_TOTAL_RESOURCE_CHARACTERS) {
                "Skill 文本资源过大：${skill.id}"
            }
            require(skill.resources.all { it.path.isNotBlank() && it.content.length <= MAX_RESOURCE_CHARACTERS }) {
                "Skill 文本资源无效：${skill.id}"
            }
        }
        listOf(
                state.bindings.summarySkillId,
                state.bindings.translationSkillId,
                state.bindings.chatSkillId,
                state.bindings.articleAnalysisSkillId,
            )
            .filterNotNull()
            .forEach { binding -> require(binding in ids) { "Skill binding 指向不存在的 Skill：$binding" } }
    }

    private fun encodeState(state: LlmSkillState): String =
        JSONObject()
            .put("version", STATE_VERSION)
            .put("bindings", encodeBindings(state.bindings))
            .put("skills", JSONArray().apply { state.skills.forEach { put(encodeSkill(it)) } })
            .toString()

    private fun decodeState(raw: String): LlmSkillState {
        val root = JSONObject(raw)
        val array = root.optJSONArray("skills") ?: JSONArray()
        val skills = buildList {
            for (index in 0 until array.length()) add(decodeSkill(array.getJSONObject(index)))
        }
        return LlmSkillState(skills = skills, bindings = decodeBindings(root.optJSONObject("bindings")))
    }

    private fun encodeSkill(skill: LlmSkillRecord): JSONObject =
        JSONObject()
            .put("id", skill.id)
            .put("description", skill.description)
            .put("enabled", skill.enabled)
            .put("instructions", skill.instructions)
            .put("sourceFileName", skill.sourceFileName ?: JSONObject.NULL)
            .put("license", skill.license ?: JSONObject.NULL)
            .put("compatibility", skill.compatibility ?: JSONObject.NULL)
            .put("allowedTools", skill.allowedTools ?: JSONObject.NULL)
            .put("hasScripts", skill.hasScripts)
            .put("contentHash", skill.contentHash)
            .put("installedAt", skill.installedAt)
            .put("updatedAt", skill.updatedAt)
            .put("metadata", JSONObject(skill.metadata))
            .put(
                "resources",
                JSONArray().apply {
                    skill.resources.forEach { resource ->
                        put(JSONObject().put("path", resource.path).put("content", resource.content))
                    }
                },
            )

    private fun decodeSkill(json: JSONObject): LlmSkillRecord {
        val metadataJson = json.optJSONObject("metadata") ?: JSONObject()
        val metadata = buildMap {
            metadataJson.keys().forEach { key -> put(key, metadataJson.optString(key)) }
        }
        val resourceArray = json.optJSONArray("resources") ?: JSONArray()
        val resources = buildList {
            for (index in 0 until resourceArray.length()) {
                val resource = resourceArray.getJSONObject(index)
                add(LlmSkillResource(resource.getString("path"), resource.getString("content")))
            }
        }
        return LlmSkillRecord(
            id = json.getString("id"),
            description = json.getString("description"),
            enabled = json.optBoolean("enabled", true),
            instructions = json.getString("instructions"),
            resources = resources,
            sourceFileName = json.optNullableString("sourceFileName"),
            license = json.optNullableString("license"),
            compatibility = json.optNullableString("compatibility"),
            allowedTools = json.optNullableString("allowedTools"),
            metadata = metadata,
            hasScripts = json.optBoolean("hasScripts", false),
            contentHash = json.optString("contentHash"),
            installedAt = json.optLong("installedAt"),
            updatedAt = json.optLong("updatedAt"),
        )
    }

    private fun encodeBindings(bindings: LlmSkillBindings): JSONObject =
        JSONObject()
            .put("summary", bindings.summarySkillId ?: JSONObject.NULL)
            .put("translation", bindings.translationSkillId ?: JSONObject.NULL)
            .put("chat", bindings.chatSkillId ?: JSONObject.NULL)
            .put("articleAnalysis", bindings.articleAnalysisSkillId ?: JSONObject.NULL)

    private fun decodeBindings(json: JSONObject?): LlmSkillBindings =
        LlmSkillBindings(
            summarySkillId = json?.optNullableString("summary"),
            translationSkillId = json?.optNullableString("translation"),
            chatSkillId = json?.optNullableString("chat"),
            articleAnalysisSkillId = json?.optNullableString("articleAnalysis"),
        )

    private fun JSONObject.optNullableString(key: String): String? =
        takeUnless { isNull(key) }?.optString(key)?.trim()?.takeIf(String::isNotBlank)

    private data class ImportedSkill(
        val parsed: LlmSkillParser.Parsed,
        val markdown: String,
        val resources: List<LlmSkillResource>,
        val hasScripts: Boolean,
    )

    companion object {
        private const val STATE_VERSION = 1
        internal const val MAX_SKILLS = 100
        private const val MAX_IMPORT_BYTES = 6_000_000
        private const val MAX_UNCOMPRESSED_BYTES = 12_000_000L
        private const val MAX_ARCHIVE_ENTRIES = 256
        private const val MAX_RESOURCE_CHARACTERS = 300_000
        private const val MAX_TOTAL_RESOURCE_CHARACTERS = 1_200_000
        private const val MAX_BACKUP_INSTRUCTION_CHARACTERS = 500_000
        private val VALID_SKILL_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val SAFE_TEXT_EXTENSIONS =
            listOf(".md", ".txt", ".json", ".yaml", ".yml", ".csv", ".xml")
    }
}
