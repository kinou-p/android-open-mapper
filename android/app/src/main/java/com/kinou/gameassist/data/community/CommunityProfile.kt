package com.kinou.gameassist.data.community

import com.google.gson.annotations.SerializedName

data class CommunityProfileSummary(
    val id: String?,
    val title: String?,
    val description: String?,
    @SerializedName("game_name") val gameName: String?,
    @SerializedName("package_name") val packageName: String?,
    @SerializedName("author_name") val authorName: String?,
    @SerializedName("controller_type") val controllerType: String = "Universal",
    @SerializedName("likes_count") var likesCount: Int = 0,
    @SerializedName("dislikes_count") var dislikesCount: Int = 0,
    @SerializedName("downloads_count") var downloadsCount: Int = 0,
    @SerializedName("created_at") val createdAt: Long = 0L,
    @SerializedName("updated_at") val updatedAt: Long = 0L,
    var userVote: Int = 0 // Local state: 1 for like, -1 for dislike, 0 for none
)

data class CommunityProfileDetail(
    val id: String?,
    val title: String?,
    val description: String?,
    @SerializedName("game_name") val gameName: String?,
    @SerializedName("package_name") val packageName: String?,
    @SerializedName("author_name") val authorName: String?,
    @SerializedName("controller_type") val controllerType: String = "Universal",
    @SerializedName("profile_json") val profileJson: String?,
    @SerializedName("likes_count") val likesCount: Int = 0,
    @SerializedName("dislikes_count") val dislikesCount: Int = 0,
    @SerializedName("downloads_count") val downloadsCount: Int = 0,
    @SerializedName("created_at") val createdAt: Long = 0L,
    @SerializedName("updated_at") val updatedAt: Long = 0L
)

data class CommunityListResponse(
    val success: Boolean,
    val page: Int = 1,
    val limit: Int = 20,
    val total: Int = 0,
    val profiles: List<CommunityProfileSummary> = emptyList(),
    val error: String? = null
)

data class CommunityDetailResponse(
    val success: Boolean,
    val profile: CommunityProfileDetail? = null,
    val error: String? = null
)

data class VoteResponse(
    val success: Boolean,
    val likes: Int = 0,
    val dislikes: Int = 0,
    @SerializedName("currentVote") val currentVote: Int = 0,
    val error: String? = null
)

data class PublishProfileRequest(
    val title: String,
    val description: String,
    @SerializedName("game_name") val gameName: String,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("author_name") val authorName: String,
    @SerializedName("controller_type") val controllerType: String,
    @SerializedName("profile_json") val profileJson: String,
    @SerializedName("deviceToken") var deviceToken: String? = null
)

data class PublishProfileResponse(
    val success: Boolean,
    val id: String? = null,
    val message: String? = null,
    val error: String? = null
)
