package com.xiaomanjun.sleepdownschedule.feature.classsync

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json

/** 班级同步 API 客户端。只使用 HttpURLConnection，避免为单个功能引入新的网络框架。 */
internal class ClassSyncClient(rawBaseUrl: String) {
    private val baseUrl = normalizeBaseUrl(rawBaseUrl)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun createClass(request: CreateClassRequest): CreateClassResponse {
        val body = json.encodeToString(CreateClassRequest.serializer(), request)
        val text = call("POST", "/api/v1/classes", body)
        return decode(text) { json.decodeFromString(CreateClassResponse.serializer(), it) }
    }

    fun joinClass(code: String, request: JoinClassRequest): JoinClassResponse {
        val body = json.encodeToString(JoinClassRequest.serializer(), request)
        val text = call("POST", "/api/v1/classes/${encode(code)}/join", body)
        return decode(text) { json.decodeFromString(JoinClassResponse.serializer(), it) }
    }

    fun fetchSchedule(code: String, knownVersion: Int, memberId: String = ""): FetchScheduleResponse {
        val params = buildList {
            if (knownVersion > 0) add("version=$knownVersion")
            if (memberId.isNotBlank()) add("memberId=${encode(memberId)}")
        }
        val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val text = call("GET", "/api/v1/classes/${encode(code)}/schedule$suffix", null)
        return decode(text) { json.decodeFromString(FetchScheduleResponse.serializer(), it) }
    }

    fun publishSchedule(code: String, adminToken: String, payload: String): PublishScheduleResponse {
        val body = json.encodeToString(
            PublishScheduleRequest.serializer(),
            PublishScheduleRequest(payload = payload)
        )
        val text = call("PUT", "/api/v1/classes/${encode(code)}/schedule", body, adminToken)
        return decode(text) { json.decodeFromString(PublishScheduleResponse.serializer(), it) }
    }

    fun adminOverview(code: String, adminToken: String): AdminOverviewResponse {
        val text = call("GET", "/api/v1/classes/${encode(code)}/admin", null, adminToken)
        return decode(text) { json.decodeFromString(AdminOverviewResponse.serializer(), it) }
    }

    fun updateClass(code: String, adminToken: String, request: UpdateClassRequest): ClassInfoResponse {
        val body = json.encodeToString(UpdateClassRequest.serializer(), request)
        val text = call("PATCH", "/api/v1/classes/${encode(code)}", body, adminToken)
        return decode(text) { json.decodeFromString(ClassInfoResponse.serializer(), it) }
    }

    fun deleteClass(code: String, adminToken: String) {
        call("DELETE", "/api/v1/classes/${encode(code)}", null, adminToken)
    }

    private inline fun <T> decode(text: String, block: (String) -> T): T = try {
        block(text)
    } catch (error: Exception) {
        throw ClassSyncException("服务器返回了无法识别的数据", error)
    }

    private fun call(method: String, path: String, body: String?, adminToken: String? = null): String {
        val connection = try {
            (URL(baseUrl + path).openConnection() as HttpURLConnection)
        } catch (error: Exception) {
            throw ClassSyncException("服务器地址无效，请检查后重试", error)
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (adminToken != null) connection.setRequestProperty("Authorization", "Bearer $adminToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readLimited(MaxResponseBytes) }
                ?.toString(Charsets.UTF_8)
                .orEmpty()
            if (status !in 200..299) throw ClassSyncException(errorMessage(status, text))
            text
        } catch (error: ClassSyncException) {
            throw error
        } catch (error: IOException) {
            throw ClassSyncException("无法连接班级服务器，请检查网络与服务器地址", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(status: Int, text: String): String {
        val serverMessage = runCatching {
            json.decodeFromString(ApiErrorDto.serializer(), text).message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (serverMessage != null) return serverMessage
        return when (status) {
            401 -> "管理员身份已失效，请重新创建班级"
            403 -> "没有权限执行此操作"
            404 -> "班级不存在，请核对班级号"
            409 -> "课表已被更新，请刷新后重试"
            413 -> "课表内容过大"
            429 -> "请求过于频繁，请稍后再试"
            else -> "班级服务器返回错误（HTTP $status）"
        }
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "服务器返回内容过大" }
        }
        return output.toByteArray()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val MaxResponseBytes = 8 * 1024 * 1024

        fun normalizeBaseUrl(raw: String): String {
            var value = raw.trim()
            if (value.isBlank()) throw ClassSyncException("请先填写班级服务器地址")
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "https://$value"
            }
            value = value.trimEnd('/')
            runCatching { URL(value) }.getOrElse {
                throw ClassSyncException("服务器地址无效，请填写完整地址")
            }
            return value
        }
    }
}