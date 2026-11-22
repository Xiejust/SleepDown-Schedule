package com.xiaomanjun.sleepdownschedule.feature.classsync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ClassSummaryDto(
    val code: String,
    val displayCode: String = "",
    val name: String,
    val teacher: String = "",
    val joinPasswordRequired: Boolean = false,
    val memberCount: Int = 0,
    val scheduleVersion: Int = 0,
    val scheduleUpdatedAt: Long? = null,
)

@Serializable
internal data class CreateClassRequest(
    val name: String,
    val teacher: String? = null,
    val joinPassword: String? = null,
    val nickname: String? = null,
)

@Serializable
internal data class CreateClassResponse(
    val code: String,
    val displayCode: String = "",
    val adminToken: String,
    @SerialName("class") val classInfo: ClassSummaryDto,
)

@Serializable
internal data class JoinClassRequest(
    val password: String? = null,
    val nickname: String? = null,
    val memberId: String? = null,
)

@Serializable
internal data class ScheduleSnapshotDto(
    val version: Int,
    val payload: String,
    val updatedAt: Long,
)

@Serializable
internal data class JoinClassResponse(
    val memberId: String,
    @SerialName("class") val classInfo: ClassSummaryDto,
    val schedule: ScheduleSnapshotDto? = null,
)

@Serializable
internal data class FetchScheduleResponse(
    val notModified: Boolean = false,
    val version: Int = 0,
    @SerialName("class") val classInfo: ClassSummaryDto? = null,
    val schedule: ScheduleSnapshotDto? = null,
)

@Serializable
internal data class PublishScheduleRequest(
    val payload: String,
    val expectedVersion: Int? = null,
)

@Serializable
internal data class PublishScheduleResponse(
    val version: Int,
    val updatedAt: Long,
    @SerialName("class") val classInfo: ClassSummaryDto? = null,
)

@Serializable
internal data class UpdateClassRequest(
    val name: String? = null,
    val teacher: String? = null,
    val joinPassword: String? = null,
)

@Serializable
internal data class ClassInfoResponse(
    @SerialName("class") val classInfo: ClassSummaryDto,
)

@Serializable
internal data class ClassMemberDto(
    val memberId: String,
    val nickname: String = "",
    val role: String = "member",
    val joinedAt: Long = 0,
    val lastSeenAt: Long = 0,
)

@Serializable
internal data class AdminOverviewResponse(
    @SerialName("class") val classInfo: ClassSummaryDto,
    val members: List<ClassMemberDto> = emptyList(),
)

@Serializable
internal data class ApiErrorDto(
    val error: String = "",
    val message: String = "",
)

internal class ClassSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 本地保存的班级绑定信息。管理员令牌只保存在本机，不进入备份。 */
internal data class ClassSyncSettings(
    val serverUrl: String = "",
    val classCode: String = "",
    val className: String = "",
    val teacher: String = "",
    val role: String = "",
    val adminToken: String = "",
    val memberId: String = "",
    val nickname: String = "",
    val linkedScheduleId: Int = -1,
    val lastVersion: Int = 0,
    val lastSyncedAt: Long = 0L,
    val memberCount: Int = 0,
    val autoPublish: Boolean = false,
    val lastPublishedHash: String = "",
) {
    val isJoined: Boolean get() = classCode.isNotBlank() && serverUrl.isNotBlank()
    val isAdmin: Boolean get() = role == "admin"
}