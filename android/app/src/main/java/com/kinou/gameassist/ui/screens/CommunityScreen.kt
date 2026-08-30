package com.kinou.gameassist.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.kinou.gameassist.data.community.*
import com.kinou.gameassist.data.model.GameProfile
import com.kinou.gameassist.data.repository.ProfileRepository
import com.kinou.gameassist.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    repository: ProfileRepository,
    localProfiles: List<GameProfile>,
    onProfileImported: (GameProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { CommunityApiClient(context) }

    var profiles by remember { mutableStateOf<List<CommunityProfileSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var selectedGameFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSort by remember { mutableStateOf("popular") } // popular, recent, downloads

    var showPublishDialog by remember { mutableStateOf(false) }

    val gameFilters = listOf(
        "Tous" to null,
        "CoD Mobile" to "com.activision.callofduty.shooter",
        "Warzone" to "warzone",
        "PUBG" to "pubg",
        "Genshin" to "genshin"
    )

    val sortOptions = listOf(
        "🔥 Populaires" to "popular",
        "🆕 Récents" to "recent",
        "📥 Téléchargés" to "downloads"
    )

    fun loadProfiles() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val result = apiClient.fetchProfiles(
                game = selectedGameFilter,
                search = searchQuery.ifBlank { null },
                sort = selectedSort
            )
            result.onSuccess { response ->
                profiles = response.profiles
                isLoading = false
            }.onFailure { ex ->
                errorMessage = ex.localizedMessage ?: "Impossible de joindre la communauté"
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedGameFilter, selectedSort) {
        loadProfiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = NeonCyan)
                        Text("Communauté", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { loadProfiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = TextPrimary)
                    }
                    Button(
                        onClick = { showPublishDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Partager", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    loadProfiles()
                },
                placeholder = { Text("Rechercher un profil, jeu, créateur...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            loadProfiles()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Effacer", tint = TextSecondary)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                ),
                singleLine = true
            )

            // 2. Game Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(gameFilters) { (label, pkg) ->
                    val isSelected = selectedGameFilter == pkg
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedGameFilter = pkg },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) NeonCyan else DarkCardBorder
                        )
                    )
                }
            }

            // 3. Sort Options Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sortOptions.forEach { (label, sortKey) ->
                    val isSelected = selectedSort == sortKey
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NeonCyan else Color.Transparent)
                            .clickable { selectedSort = sortKey }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) DarkBackground else TextSecondary
                        )
                    }
                }
            }

            // 4. Profiles List
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonCyan)
                }
            } else if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = NeonPink, modifier = Modifier.size(36.dp))
                        Text(errorMessage ?: "", color = TextSecondary, fontSize = 13.sp)
                        Button(
                            onClick = { loadProfiles() },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Réessayer", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (profiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Inbox, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                        Text("Aucun profil trouvé pour ce filtre.", color = TextSecondary, fontSize = 14.sp)
                        Text("Soyez le premier à partager votre configuration !", color = NeonCyan, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(profiles, key = { it.id }) { item ->
                        CommunityProfileCard(
                            profile = item,
                            onVote = { voteVal ->
                                scope.launch {
                                    val res = apiClient.vote(item.id, voteVal)
                                    res.onSuccess { voteRes ->
                                        item.likesCount = voteRes.likes
                                        item.dislikesCount = voteRes.dislikes
                                        item.userVote = voteRes.currentVote
                                        // trigger UI recomposition
                                        profiles = profiles.toList()
                                    }
                                }
                            },
                            onImport = {
                                scope.launch {
                                    val detailRes = apiClient.getProfileDetail(item.id)
                                    detailRes.onSuccess { detail ->
                                        apiClient.trackDownload(item.id)
                                        item.downloadsCount += 1
                                        profiles = profiles.toList()

                                        val imported = repository.importProfileFromJson(detail.profileJson)
                                        if (imported != null) {
                                            onProfileImported(imported)
                                            Toast.makeText(context, "Profil \"${imported.name}\" importé avec succès !", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Erreur lors du décodage du profil", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { ex ->
                                        Toast.makeText(context, "Erreur téléchargement: ${ex.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // 5. Publish Dialog
        if (showPublishDialog) {
            PublishProfileDialog(
                localProfiles = localProfiles,
                onDismiss = { showPublishDialog = false },
                onPublish = { req ->
                    scope.launch {
                        val res = apiClient.publishProfile(req)
                        res.onSuccess {
                            Toast.makeText(context, "Profil publié sur le Hub Communautaire !", Toast.LENGTH_LONG).show()
                            showPublishDialog = false
                            loadProfiles()
                        }.onFailure { ex ->
                            Toast.makeText(context, "Erreur: ${ex.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun CommunityProfileCard(
    profile: CommunityProfileSummary,
    onVote: (Int) -> Unit,
    onImport: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val dateStr = if (profile.createdAt > 0) dateFormat.format(Date(profile.createdAt)) else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Surface(
                            color = NeonCyan.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                profile.gameName,
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = NeonOrange.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                profile.controllerType,
                                color = NeonOrange,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Import Button
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Importer", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            if (!profile.description.isNullOrBlank()) {
                Text(profile.description, color = TextSecondary, fontSize = 12.sp, maxLines = 2)
            }

            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 1.dp)

            // Footer: Author, Downloads, Likes & Dislikes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Par ${profile.authorName}", color = TextSecondary, fontSize = 11.sp)
                    if (dateStr.isNotEmpty()) {
                        Text("• $dateStr", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Downloads
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                        Text("${profile.downloadsCount}", color = TextSecondary, fontSize = 11.sp)
                    }

                    // Like Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onVote(if (profile.userVote == 1) 0 else 1) }
                            .background(if (profile.userVote == 1) NeonGreen.copy(alpha = 0.2f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.ThumbUp,
                            contentDescription = "Like",
                            tint = if (profile.userVote == 1) NeonGreen else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "${profile.likesCount}",
                            color = if (profile.userVote == 1) NeonGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (profile.userVote == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    // Dislike Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onVote(if (profile.userVote == -1) 0 else -1) }
                            .background(if (profile.userVote == -1) NeonPink.copy(alpha = 0.2f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.ThumbDown,
                            contentDescription = "Dislike",
                            tint = if (profile.userVote == -1) NeonPink else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "${profile.dislikesCount}",
                            color = if (profile.userVote == -1) NeonPink else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (profile.userVote == -1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PublishProfileDialog(
    localProfiles: List<GameProfile>,
    onDismiss: () -> Unit,
    onPublish: (PublishProfileRequest) -> Unit
) {
    val gson = remember { Gson() }
    var selectedProfile by remember { mutableStateOf(localProfiles.firstOrNull()) }
    var title by remember(selectedProfile) { mutableStateOf(selectedProfile?.name ?: "") }
    var description by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var controllerType by remember { mutableStateOf("Xbox / PS5 / Générique") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager un Profil", fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sélectionnez le profil local à publier :", color = TextSecondary, fontSize = 12.sp)

                // Select profile dropdown / list
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(localProfiles) { prof ->
                        val active = prof.id == selectedProfile?.id
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedProfile = prof
                                title = prof.name
                            },
                            label = { Text(prof.name, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = DarkBackground,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre du profil (ex: CODM Pro Flick 180°)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = authorName,
                    onValueChange = { authorName = it },
                    label = { Text("Votre pseudo / auteur") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / conseils de jeu (optionnel)") },
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                )

                OutlinedTextField(
                    value = controllerType,
                    onValueChange = { controllerType = it },
                    label = { Text("Type de manette conseillée") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val prof = selectedProfile
                    if (prof != null && title.isNotBlank() && authorName.isNotBlank()) {
                        val json = gson.toJson(prof)
                        onPublish(
                            PublishProfileRequest(
                                title = title.trim(),
                                description = description.trim(),
                                gameName = prof.name,
                                packageName = prof.packageName,
                                authorName = authorName.trim(),
                                controllerType = controllerType.trim(),
                                profileJson = json
                            )
                        )
                    }
                },
                enabled = selectedProfile != null && title.isNotBlank() && authorName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Publier", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = TextSecondary)
            }
        },
        containerColor = DarkCard
    )
}
