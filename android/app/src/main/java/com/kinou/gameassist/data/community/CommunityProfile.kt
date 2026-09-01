package com.kinou.gameassist.data.community

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class CommunityProfileSummary(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("controller_type") val controllerType: String = "Universal",
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("dislikes_count") val dislikesCount: Int = 0,
    @SerialName("downloads_count") val downloadsCount: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @Transient val userVote: Int = 0 // Local state: 1 for like, -1 for dislike, 0 for none
)

@Serializable
data class CommunityProfileDetail(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("game_name") val gameName: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("controller_type") val controllerType: String = "Universal",
    @SerialName("profile_json") val profileJson: String? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("dislikes_count") val dislikesCount: Int = 0,
    @SerialName("downloads_count") val downloadsCount: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L
)

@Serializable
data class CommunityListResponse(
    val success: Boolean,
    val page: Int = 1,
    val limit: Int = 20,
    val total: Int = 0,
    val profiles: List<CommunityProfileSummary> = emptyList(),
    val error: String? = null
)

@Serializable
data class CommunityDetailResponse(
    val success: Boolean,
    val profile: CommunityProfileDetail? = null,
    val error: String? = null
)

@Serializable
data class VoteResponse(
    val success: Boolean,
    val likes: Int = 0,
    val dislikes: Int = 0,
    @SerialName("currentVote") val currentVote: Int = 0,
    val error: String? = null
)

@Serializable
data class PublishProfileRequest(
    val title: String,
    val description: String,
    @SerialName("game_name") val gameName: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("controller_type") val controllerType: String,
    @SerialName("profile_json") val profileJson: String,
    @SerialName("deviceToken") var deviceToken: String? = null
)

@Serializable
data class PublishProfileResponse(
    val success: Boolean,
    val id: String? = null,
    val message: String? = null,
    val error: String? = null
)
